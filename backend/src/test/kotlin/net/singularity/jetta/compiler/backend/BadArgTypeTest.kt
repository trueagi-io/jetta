package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end regression tests for eval-time `BadArgType` errors (phase D2.2).
 *
 * D2.2 tier (i): a grounded arithmetic op applied to a concrete non-numeric operand (String) is a
 * type error — the compiler emits the inert `(Error <expr> (BadArgType <pos> Number String))` atom
 * (compile-time static-error strategy, keeping the inline `IADD`/`DADD` fast path for well-typed
 * operands). Previously `(+ 2 "String")` crashed codegen (`castIfNeeded` String→INT `TODO`).
 *
 * Each `!(assertEqualToResult …)` throws AssertionError from `__main` on a wrong answer, so a green
 * `invoke` IS the assertion.
 */
class BadArgTypeTest : GeneratorTestBase() {

    private fun run(name: String, code: String, allowWarnings: Boolean = false) {
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach(::println)
                if (!allowWarnings) assertTrue(messageCollector.list().isEmpty())
                val classes = result.toMap().toClasses()
                JettaProgram.init(name)
                classes[name]!!.getMethod("__main").invoke(null)
            }
    }

    private fun runLenient(name: String, code: String) = run(name, code, allowWarnings = true)

    /** A String operand to `+`/`*` yields `(Error … (BadArgType pos Number String))`. */
    @Test
    fun `grounded arithmetic with a string operand is a BadArgType error`() = run(
        "BadArgArith",
        """
            !(assertEqualToResult
              (+ 2 "String")
              ((Error (+ 2 "String") (BadArgType 2 Number String))))
            !(assertEqualToResult
              (* "x" 3)
              ((Error (* "x" 3) (BadArgType 1 Number String))))
        """.trimIndent()
    )

    /**
     * The error is identity-precise: an identical `(+ …)` appearing inside quoted expected data is
     * emitted verbatim, NOT turned into a nested error (guards the structural-replacement hazard).
     */
    @Test
    fun `an identical arithmetic term in quoted data is preserved`() = run(
        "BadArgQuoted",
        """
            !(assertEqualToResult
              (+ 1 "s")
              ((Error (+ 1 "s") (BadArgType 2 Number String))))
        """.trimIndent()
    )

    /** Well-typed numeric arithmetic stays on the fast path (no false positives). */
    @Test
    fun `well-typed arithmetic is unaffected`() = run(
        "BadArgOk",
        """
            !(assertEqual (+ 2 3) 5)
            !(assertEqual (* 2 (- 7 3)) 8)
        """.trimIndent()
    )

    /**
     * D2.3: a `(: f (-> …))`-declared user function type-checks its args before reducing. `(Add S
     * Z)` errors (S : (-> Nat Nat), param 1 wants Nat) instead of matching `(= (Add $x Z) $x)`;
     * a well-typed call reduces normally; a gradual/undeclared argument (`Something`) is allowed.
     * Types are declared before the runs (JeTTa loads all facts up front; b5's run-before-declare
     * ordering is a separate top-level-semantics concern).
     */
    @Test
    fun `declared user function errors on a mistyped argument`() = runLenient(
        "BadArgUserFn",
        $$"""
            (: Z Nat)
            (: S (-> Nat Nat))
            (: Add (-> Nat Nat Nat))
            (= (Add $x Z) $x)
            (= (Add $x (S $y)) (Add (S $x) $y))
            !(assertEqualToResult (Add S Z) ((Error (Add S Z) (BadArgType 1 Nat (-> Nat Nat)))))
            !(assertEqual (Add (S Z) Z) (S Z))
            !(assertEqual (Add Z (S Z)) (S Z))
            !(assertEqual (Add Something Z) Something)
        """.trimIndent()
    )

    /** An undeclared (untyped) function is NOT instrumented — reduces without a type check. */
    @Test
    fun `undeclared function is not type-checked`() = runLenient(
        "BadArgUntyped",
        $$"""
            (= (pick $x $y) $x)
            !(assertEqual (pick A B) A)
        """.trimIndent()
    )
}
