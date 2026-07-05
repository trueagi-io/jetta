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
            // d/dx (x + x*x) = 1 + (1*x + x*1). `d` has exclusive clause heads and is only
            // ever called with ground args, so it compiles to scalar dispatch — the
            // deterministic reduction yields ONE value, not a singleton bag (matches hyperon).
            assertEquals("(Plus (Lit 1) (Plus (Mul (Lit 1) x) (Mul x (Lit 1))))", it.result.toString())
        }
    }

    @Test
    fun `evaluate the derivative at a point`() {
        differentiator().eval("""!(ev (d (Plus x (Mul x x))))""").let {
            assertTrue(it.isSuccess)
            assertEquals("7", it.result.toString())  // 1 + 2x at x=3 (scalar d and ev)
        }
    }

    @Test
    fun `derivative of a cube at a point`() {
        differentiator().eval("""!(ev (d (Mul x (Mul x x))))""").let {
            assertTrue(it.isSuccess)
            assertEquals("27", it.result.toString())  // 3x^2 at x=3 (scalar d and ev)
        }
    }

    /**
     * Arithmetic composes directly over `(ev (d …))`. This was a VerifyError while `d`
     * was force-marked multivalued: `(ev (d …))` was then a `List` and `+` received a
     * list operand. With `d` classified functional (exclusive heads, only ground call
     * args → scalar dispatch), `(ev (d …))` is a plain `Int` and folds into arithmetic.
     */
    @Test
    fun `arithmetic composes over the scalar derivative`() {
        differentiator().eval(
            """!(+ (ev (d (Mul x (Mul x x)))) (ev (d (Plus x (Mul x x)))))"""
        ).let {
            assertTrue(it.isSuccess)
            assertEquals("34", it.result.toString())  // 27 + 7
        }
    }

    /**
     * The iterated differentiator — the decisive symbolic-rewriting benchmark shape.
     * A guarded-recursion loop accumulates `(ev (d …))` per step: previously unrunnable
     * (arithmetic-over-multivalued), now a tight scalar reduction that recomputes the
     * full derivative each iteration (no tabling: `d` returns an Atom, not a primitive).
     */
    @Test
    fun `iterated differentiation accumulates a scalar sum`() {
        val r = differentiator()
        r.eval(
            """
            (: loop (-> Int Int Int))
            (= (loop _n _acc)
               (if (== _n 0) _acc
                   (loop (- _n 1) (+ _acc (ev (d (Mul x (Mul x x))))))))
            """.trimIndent().replace('_', '$')
        )
        r.eval("""!(loop 100 0)""").let {
            assertTrue(it.isSuccess)
            assertEquals("2700", it.result.toString())  // 100 * 27
        }
    }
}
