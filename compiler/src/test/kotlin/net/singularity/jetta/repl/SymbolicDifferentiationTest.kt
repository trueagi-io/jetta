package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Symbolic differentiation (SICP classic) by pure term rewriting, then numeric
 * evaluation of the derivative — mirrors examples/symbolic/Derivative.metta. No eval:
 * `d` and `ev` are ordinary compiled functions; structural clause-head matching drives
 * differentiation; `ev` reduces the derivative to a number. Regression guard for the
 * codegen fixes that make this work (destructured-var unwrap, branch-result coercion).
 */
class SymbolicDifferentiationTest {

    private fun differentiator(): Repl = ReplImpl().apply {
        eval(
            """
            (= (d x) (Lit 1))
            (= (d (Lit _n)) (Lit 0))
            (= (d (Plus _a _b)) (Plus (d _a) (d _b)))
            (= (d (Mul _a _b)) (Plus (Mul (d _a) _b) (Mul _a (d _b))))

            (: ev (-> Atom Int))
            (= (ev x) 3)
            (= (ev (Lit _n)) _n)
            (= (ev (Plus _a _b)) (+ (ev _a) (ev _b)))
            (= (ev (Mul _a _b)) (* (ev _a) (ev _b)))
            """.trimIndent().replace('_', '$')
        )
    }

    @Test
    fun `derivative is a single symbolic expression`() {
        differentiator().eval("""!(d (Plus x (Mul x x)))""").let {
            assertTrue(it.isSuccess)
            // d/dx (x + x*x) = 1 + (1*x + x*1) — deterministic, one result.
            assertEquals("[(Plus (Lit 1) (Plus (Mul (Lit 1) x) (Mul x (Lit 1))))]", it.result.toString())
        }
    }

    @Test
    fun `evaluate the derivative at a point`() {
        differentiator().eval("""!(ev (d (Plus x (Mul x x))))""").let {
            assertTrue(it.isSuccess)
            assertEquals("[7]", it.result.toString())  // 1 + 2x at x=3
        }
    }

    @Test
    fun `derivative of a cube at a point`() {
        differentiator().eval("""!(ev (d (Mul x (Mul x x))))""").let {
            assertTrue(it.isSuccess)
            assertEquals("[27]", it.result.toString())  // 3x^2 at x=3
        }
    }
}
