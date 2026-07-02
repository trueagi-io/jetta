package net.singularity.jetta.compiler.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auto-tabling (memoization) on the AOT path. A pure, deterministic, state-independent,
 * recursive function is compiled as a wrapper `f` (memo cache keyed on its boxed args)
 * delegating to `f$impl` on a miss — turning naive exponential recursion into O(distinct
 * args) while staying result-identical. See Generator.computeMemoizable / emitMemoWrapper.
 */
class MemoTablingTest : GeneratorTestBase() {
    private val fib = """
        (: fib (-> Int Int))
        (= (fib _n) (if (< _n 2) _n (+ (fib (- _n 1)) (fib (- _n 2)))))
    """.trimIndent().replace('_', '$')

    private fun Class<*>.hasImpl(name: String) =
        declaredMethods.any { it.name == "$name\$impl" }

    @Test
    fun `recursive pure fib is tabled and stays correct`() {
        compile("Fib.metta", fib, autoTable = true).let { (result, mc) ->
            assertTrue(mc.list().isEmpty())
            val clazz = result[0].getClass()
            assertTrue(clazz.hasImpl("fib"), "fib should be tabled (fib\$impl present)")
            val fibM = clazz.getMethod("fib", Int::class.java)
            // Correct even at N where naive recursion would be ~2.7M/120M calls — the point.
            assertEquals(9227465, fibM.invoke(null, 35))
            assertEquals(102334155, fibM.invoke(null, 40))
            assertEquals(0, fibM.invoke(null, 0))
            assertEquals(1, fibM.invoke(null, 1))
        }
    }

    @Test
    fun `tabling is off without autoTable (the REPL or JIT path)`() {
        compile("FibNoTable.metta", fib, autoTable = false).let { (result, _) ->
            assertFalse(result[0].getClass().hasImpl("fib"), "fib must NOT be tabled when autoTable is off")
        }
    }

    @Test
    fun `a non-recursive pure function is not tabled`() {
        // dbl is pure but not recursive: memoization wouldn't pay and could grow unbounded,
        // so it is left alone (no dbl$impl), while still computing correctly.
        compile(
            "Dbl.metta",
            "(: dbl (-> Int Int))\n(= (dbl _x) (* _x 2))".replace('_', '$'),
            autoTable = true
        ).let { (result, mc) ->
            assertTrue(mc.list().isEmpty())
            val clazz = result[0].getClass()
            assertFalse(clazz.hasImpl("dbl"), "non-recursive dbl must NOT be tabled")
            assertEquals(10, clazz.getMethod("dbl", Int::class.java).invoke(null, 5))
        }
    }

    @Test
    fun `mutually recursive pure functions are tabled`() {
        compile(
            "EvenOdd.metta",
            """
            (: isEven (-> Int Int))
            (: isOdd (-> Int Int))
            (= (isEven _n) (if (== _n 0) 1 (isOdd (- _n 1))))
            (= (isOdd _n) (if (== _n 0) 0 (isEven (- _n 1))))
            """.trimIndent().replace('_', '$'),
            autoTable = true
        ).let { (result, mc) ->
            assertTrue(mc.list().isEmpty())
            val clazz = result[0].getClass()
            assertTrue(clazz.hasImpl("isEven"), "isEven should be tabled")
            assertTrue(clazz.hasImpl("isOdd"), "isOdd should be tabled")
            assertEquals(1, clazz.getMethod("isEven", Int::class.java).invoke(null, 10))
            assertEquals(0, clazz.getMethod("isEven", Int::class.java).invoke(null, 11))
        }
    }
}
