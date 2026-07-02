package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Functions that never touch the binding stack (not multivalued, no Atom params, no
 * Match / `match` / multivalued call in the body) have their per-call `Matcher.push()`/
 * `pop()` frame elided — it would be a no-op (empty push, `putAll(emptyMap)` pop). This
 * pins the CORRECTNESS of that elision: deep and mutually-recursive purely-computational
 * functions must still produce the right results.
 *
 * The complementary "still uses the frame when it must" direction is covered by
 * EnvInterpreterTest / the b4 corpus tests (multivalued + match + destructuring).
 */
class MatcherFrameElisionTest {
    private fun repl() = ReplImpl()
    private fun String.d() = replace('_', '$')

    @Test
    fun `deep self-recursion (fib) is correct with the frame elided`() {
        val r = repl()
        r.eval("(: fib (-> Int Int))\n(= (fib _n) (if (< _n 2) _n (+ (fib (- _n 1)) (fib (- _n 2)))))".d())
        r.eval("!(fib 20)").let { assertTrue(it.isSuccess, it.messages.joinToString("\n")); assertEquals(6765, it.result) }
    }

    @Test
    fun `a chain of pure functions composes correctly`() {
        val r = repl()
        r.eval("(: dbl (-> Int Int))\n(= (dbl _x) (* _x 2))\n(: quad (-> Int Int))\n(= (quad _x) (dbl (dbl _x)))".d())
        r.eval("!(quad 5)").let { assertTrue(it.isSuccess, it.messages.joinToString("\n")); assertEquals(20, it.result) }
    }

    @Test
    fun `mutual recursion is correct with the frame elided`() {
        val r = repl()
        r.eval(
            """
            (: isEven (-> Int Int))
            (: isOdd (-> Int Int))
            (= (isEven _n) (if (== _n 0) 1 (isOdd (- _n 1))))
            (= (isOdd _n) (if (== _n 0) 0 (isEven (- _n 1))))
            """.trimIndent().d()
        )
        r.eval("!(isEven 10)").let { assertTrue(it.isSuccess, it.messages.joinToString("\n")); assertEquals(1, it.result) }
        r.eval("!(isEven 11)").let { assertTrue(it.isSuccess, it.messages.joinToString("\n")); assertEquals(0, it.result) }
    }
}
