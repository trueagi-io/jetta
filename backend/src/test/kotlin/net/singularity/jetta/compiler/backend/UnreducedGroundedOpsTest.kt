package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end tests for grounded operations that CANNOT be computed and must therefore stay
 * unreduced (hyperon's `c1_grounded_basic` semantics).
 *
 * A grounded op works on numbers, so an operand that is an ordinary symbol (`ln`) or a variable
 * with no binder is not something it can consume. hyperon leaves such an application as data
 * instead of failing. Before this, `(+ ln 2)` hit a `TODO` in `FunctionGenerator.type` and took the
 * whole compiler down, so these cases are also a crash regression guard.
 *
 * Each `!(assertEqualToResult …)` throws AssertionError from `__main` on a wrong answer, so a green
 * `invoke` IS the assertion. The cases with an unknown symbol compile with a "cannot resolve"
 * warning by design — those use [runLenient].
 */
class UnreducedGroundedOpsTest : GeneratorTestBase() {

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

    /**
     * An application with an unknown head stays inert, but its grounded ARGUMENT is still reduced —
     * an unknown head does not suppress reduction of what it is applied to.
     */
    @Test
    fun `an unknown head keeps the application inert but reduces its grounded argument`() =
        runLenient(
            "UnreducedApply",
            """
                !(assertEqualToResult
                  (ln (+ 2 2))
                  ((ln 4)))
            """.trimIndent()
        )

    /** Arithmetic over a plain symbol has no value, so the enclosing comparison stays inert too. */
    @Test
    fun `an order comparison over an unreducible operand stays unreduced`() = runLenient(
        "UnreducedCompare",
        """
            !(assertEqualToResult
              (> 4 (+ ln 2))
              ((> 4 (+ ln 2))))
        """.trimIndent()
    )

    /** Same, for a variable that has no binder at all (it occurs only in the run). */
    @Test
    fun `an order comparison over a free variable stays unreduced`() = run(
        "UnreducedFreeVar",
        """
            !(assertEqualToResult
              (> 4 (+ ${'$'}x 2))
              ((> 4 (+ ${'$'}x 2))))
        """.trimIndent()
    )

    /**
     * `==` differs from the ordering operators: it compares structurally rather than staying
     * inert, so a grounded operand against an unreduced one is simply `False`.
     */
    @Test
    fun `equality against an unreducible operand is False`() = runLenient(
        "UnreducedEq",
        """
            !(assertEqualToResult
              (== 4 (+ ln 2))
              (False))
        """.trimIndent()
    )

    /**
     * A symbol operand with a DECLARED non-numeric type turns the same unreducible form into a
     * `BadArgType` error — and because `:` declarations are space facts read under the run's
     * watermark, the verdict depends on the run's position: identical expressions, one above the
     * declaration and one below it, get different answers.
     */
    @Test
    fun `a declared non-numeric operand makes the form a BadArgType error from that point on`() =
        runLenient(
            "UnreducedDeclaredType",
            """
                !(assertEqualToResult
                  (+ ln 2)
                  ((+ ln 2)))
                (: ln LN)
                !(assertEqualToResult
                  (+ ln 2)
                  ((Error (+ ln 2) (BadArgType 1 Number LN))))
            """.trimIndent()
        )

    /**
     * The computable path is untouched: real arithmetic, a numeric-parameter function and the
     * variable-pinning that lets untyped arithmetic compile all still work.
     */
    @Test
    fun `computable grounded arithmetic is unaffected`() = run(
        "UnreducedControl",
        """
            (= (sqr ${'$'}x) (* ${'$'}x ${'$'}x))
            !(assertEqualToResult
              (+ 2 (* 3 5.5))
              (18.5))
            !(assertEqualToResult
              (sqr 4)
              (16))
            !(assertEqualToResult
              (> 4 (+ 2 (* 3 5)))
              (False))
        """.trimIndent()
    )
}
