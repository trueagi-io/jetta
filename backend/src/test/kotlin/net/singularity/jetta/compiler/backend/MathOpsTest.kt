package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The reference stdlib's `*-math` family and `min-atom`/`max-atom`
 * ([net.singularity.jetta.runtime.MathOps]). Every expectation here was read off
 * hyperon 0.2.10, not assumed — the corpus files `he_math.metta` and `math.metta` pass on the
 * reference, so their assertions ARE the specification, and the edge cases below were probed
 * against the same binary.
 *
 * Each program asserts via `!(assertEqual …)`; a wrong answer throws AssertionError out of
 * `__main`, so a green `invoke` IS the assertion.
 */
class MathOpsTest : GeneratorTestBase() {

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

    /** `he_math.metta` verbatim, minus the two lines covered separately below. */
    @Test
    fun `the math family answers what hyperon answers`() = run(
        "MathFamily",
        """
            !(assertEqual (pow-math 2 3) 8.0)
            !(assertEqual (sqrt-math 9) 3.0)
            !(assertEqual (log-math 10 100) 2.0)
            !(assertEqual (trunc-math 5.6) 5.0)
            !(assertEqual (ceil-math 5.2) 6.0)
            !(assertEqual (floor-math 5.8) 5.0)
            !(assertEqual (sin-math 0) 0.0)
            !(assertEqual (asin-math 0) 0.0)
            !(assertEqual (cos-math 0) 1.0)
            !(assertEqual (acos-math 1) 0.0)
            !(assertEqual (tan-math 0) 0.0)
            !(assertEqual (atan-math 0) 0.0)
            !(assertEqual (isnan-math 0.0) False)
            !(assertEqual (isinf-math 0.0) False)
        """.trimIndent()
    )

    /**
     * `round-math` rounds away from zero on a tie. `Math.round` rounds half towards positive
     * infinity and would answer `-5` for `-5.5`, which is the trap this pins.
     */
    @Test
    fun `round-math rounds away from zero`() = run(
        "MathRound",
        """
            !(assertEqual (round-math 5.4) 5.0)
            !(assertEqual (round-math 5.6) 6.0)
            !(assertEqual (round-math 5.5) 6.0)
            !(assertEqual (round-math -5.5) -6.0)
        """.trimIndent()
    )

    /** `trunc-math` truncates towards zero, so it is not `floor` on a negative operand. */
    @Test
    fun `trunc-math truncates towards zero`() = run(
        "MathTrunc",
        """
            !(assertEqual (trunc-math -5.6) -5.0)
            !(assertEqual (ceil-math -5.2) -5.0)
            !(assertEqual (floor-math -5.8) -6.0)
        """.trimIndent()
    )

    /**
     * `abs-math` is the one operation that preserves the operand's type — an `Int` in, an
     * `Int` out. Every other operation here widens to `Double`.
     */
    @Test
    fun `abs-math preserves the operand type`() = run(
        "MathAbs",
        """
            !(assertEqual (abs-math -5) 5)
            !(assertEqual (abs-math 5) 5)
            !(assertEqual (abs-math -5.5) 5.5)
        """.trimIndent()
    )

    /** `min-atom`/`max-atom` always answer a Double, even over all-integer input. */
    @Test
    fun `min-atom and max-atom answer a double`() = run(
        "MathExtremum",
        """
            !(assertEqual (min-atom (2 6 7 4 9 3)) 2.0)
            !(assertEqual (max-atom (2 6 7 4 9 3)) 9.0)
            !(assertEqual (min-atom (2.5 6 7)) 2.5)
        """.trimIndent()
    )

    /** A non-numeric operand is the reference's positional `BadArgType`, not a crash. */
    @Test
    fun `a non-numeric operand is a BadArgType error`() = run(
        "MathBadArg",
        """
            !(assertEqualToResult
              (sqrt-math "a")
              ((Error (sqrt-math "a") (BadArgType 1 Number String))))
            !(assertEqualToResult
              (pow-math 2 "b")
              ((Error (pow-math 2 "b") (BadArgType 2 Number String))))
        """.trimIndent()
    )
}
