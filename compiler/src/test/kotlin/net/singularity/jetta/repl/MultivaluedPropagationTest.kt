package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Multivaluedness propagates to a caller at ANY depth and in ANY source order.
 *
 * `MarkMultivaluedFunctionsRewriter` used to patch callers through a table filled during one
 * pass, which reached a single level: with `a -> b -> c` written in that order and only `c`
 * superposing, `b` was marked but `a` was not, so `a` returned `b`'s bag through a scalar
 * descriptor (`ClassCastException: ArrayList cannot be cast to Grounded` at `(+ 10 (a 1))`).
 */
class MultivaluedPropagationTest {
    private fun repl() = ReplImpl()
    private fun String.d() = replace('_', '$')

    private val chainCalleeLast = """
        (= (a _x) (b _x))
        (= (b _x) (c _x))
        (= (c _x) (superpose (_x 2)))
    """.trimIndent().d()

    @Test
    fun `a caller two levels above the superpose, written first, is multivalued`() {
        val r = repl()
        r.eval(chainCalleeLast).let { assertTrue(it.isSuccess, it.messages.joinToString("\n")) }
        r.eval("""!(assertEqualToResult (a 1) (1 2))""").let {
            assertTrue(it.isSuccess, it.messages.joinToString("\n"))
        }
    }

    @Test
    fun `arithmetic over a transitively multivalued call maps over its bag`() {
        val r = repl()
        r.eval(chainCalleeLast)
        r.eval("""!(assertEqual (collapse (+ 10 (a 1))) (11 12))""").let {
            assertTrue(it.isSuccess, it.messages.joinToString("\n"))
        }
    }

    @Test
    fun `the check can fail - a wrong expected bag is reported`() {
        val r = repl()
        r.eval(chainCalleeLast)
        // A failed assertion escapes `eval` (only a top-level `(Error …)` is folded into a result).
        assertFails { r.eval("""!(assertEqual (collapse (+ 10 (a 1))) (11 99))""") }
    }

    @Test
    fun `a call at another arity does not inherit the callee's valuedness`() {
        val r = repl()
        r.eval("""
            (: gen (-> Atom Atom %Undefined%))
            (= (gen _a _b) (superpose (_a _b)))
            (= (wrap _x) (gen _x))
        """.trimIndent().d()).let { assertTrue(it.isSuccess, it.messages.joinToString("\n")) }
        r.eval("""!(assertEqual (wrap 1) (gen 1))""").let {
            assertTrue(it.isSuccess, it.messages.joinToString("\n"))
        }
        r.eval("""!(assertEqualToResult (gen 1 2) (1 2))""").let {
            assertTrue(it.isSuccess, it.messages.joinToString("\n"))
        }
    }
}
