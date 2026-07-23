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

    private fun run(name: String, code: String) {
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach(::println)
                assertTrue(messageCollector.list().isEmpty())
                val classes = result.toMap().toClasses()
                JettaProgram.init(name)
                classes[name]!!.getMethod("__main").invoke(null)
            }
    }

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
}
