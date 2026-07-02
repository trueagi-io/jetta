package net.singularity.jetta.compiler.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Type inference for arithmetic (and comparison) operands on UNTYPED functions.
 *
 * A function written without a `(: f (-> …))` signature used to leave its
 * parameters and its return position at the dynamic top `Atom`, so codegen emitted
 * an `ireturn`/`iadd` of a primitive against an `Object` descriptor → VerifyError.
 * Now:
 *  - arithmetic operands (`(+ $x $x)`) and comparison operands (`(== $m 0)`) pin an
 *    untyped variable to a numeric type, which [refineFunctionArrowTypes] lifts onto
 *    the parameter;
 *  - the return type is refined from the resolved body, treating a bare
 *    self-recursive call as carrying no information (a least-fixpoint from bottom)
 *    so tail-recursive bodies like gcd don't collapse to `Any`.
 *
 * `getMethod(name, Int::class.java)` throws unless the parameter is a primitive
 * `int`, so it doubles as an assertion that inference produced primitive slots.
 */
class UntypedArithmeticInferenceTest : GeneratorTestBase() {
    private fun String.d() = replace('_', '$')

    @Test
    fun `arithmetic-only parameter is inferred Int`() {
        // (double $x) = (+ $x $x): $x is untyped but used only in arithmetic.
        compile("Double.metta", "(= (double _x) (+ _x _x))".d()).let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), "no diagnostics expected: ${mc.list()}")
            val m = result[0].getClass().getMethod("double", Int::class.java)
            assertEquals(Integer.TYPE, m.returnType, "return must be primitive int")
            assertEquals(42, m.invoke(null, 21))
        }
    }

    @Test
    fun `untyped self-recursive fib infers Int param and Int return`() {
        val fib = "(= (fib _n) (if (< _n 2) _n (+ (fib (- _n 1)) (fib (- _n 2)))))".d()
        compile("UFib.metta", fib).let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), "no diagnostics expected: ${mc.list()}")
            val m = result[0].getClass().getMethod("fib", Int::class.java)
            assertEquals(Integer.TYPE, m.returnType, "return must be primitive int")
            assertEquals(55, m.invoke(null, 10))
            assertEquals(0, m.invoke(null, 0))
        }
    }

    @Test
    fun `untyped factorial infers Int`() {
        val fact = "(= (fact _n) (if (== _n 0) 1 (* _n (fact (- _n 1)))))".d()
        compile("UFact.metta", fact).let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), "no diagnostics expected: ${mc.list()}")
            val m = result[0].getClass().getMethod("fact", Int::class.java)
            assertEquals(120, m.invoke(null, 5))
        }
    }

    @Test
    fun `untyped gcd with a bare recursive branch infers Int return`() {
        // The else branch is a bare `(gcd …)` recursive call; without least-fixpoint
        // return inference the `if` unifies Int (base case) with the call's provisional
        // Atom and the return type collapses to Any → VerifyError.
        val gcd = "(= (gcd _a _b) (if (== _b 0) _a (gcd _b (mod _a _b))))".d()
        compile("UGcd.metta", gcd).let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), "no diagnostics expected: ${mc.list()}")
            val m = result[0].getClass().getMethod("gcd", Int::class.java, Int::class.java)
            assertEquals(Integer.TYPE, m.returnType, "return must be primitive int")
            assertEquals(12, m.invoke(null, 48, 36))
        }
    }

    @Test
    fun `untyped ackermann infers both params via comparison and arithmetic`() {
        // $n is pinned by (+ $n 1); $m's only non-recursive use is the comparison
        // (== $m 0) — the resolve-pass comparison symmetry is what types it Int.
        val ack = """
            (= (ack _m _n)
               (if (== _m 0) (+ _n 1)
                   (if (== _n 0) (ack (- _m 1) 1)
                       (ack (- _m 1) (ack _m (- _n 1))))))
        """.trimIndent().d()
        compile("UAck.metta", ack).let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), "no diagnostics expected: ${mc.list()}")
            val m = result[0].getClass().getMethod("ack", Int::class.java, Int::class.java)
            assertEquals(Integer.TYPE, m.returnType, "return must be primitive int")
            assertEquals(9, m.invoke(null, 2, 3))
            assertEquals(125, m.invoke(null, 3, 4))
        }
    }

    @Test
    fun `float literal makes an untyped operand Double`() {
        // 1.5 forces float contagion: $x and the result are Double, not Int.
        compile("UAddD.metta", "(= (addd _x) (+ _x 1.5))".d()).let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), "no diagnostics expected: ${mc.list()}")
            val m = result[0].getClass().getMethod("addd", Double::class.java)
            assertEquals(Double::class.javaPrimitiveType, m.returnType, "return must be primitive double")
            assertEquals(4.0, m.invoke(null, 2.5))
        }
    }
}
