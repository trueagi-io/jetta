package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A deterministic destructuring function whose body is an `if` with a HETEROGENEOUS pair
 * of arms — a destructured value on one side, a recursive call on the other, e.g.
 * `(if (== _key _k) _v (lookup _key _rest))`.
 *
 * `lookup` has an Int (grounded-value) return and mutually-exclusive clauses, so it
 * compiles to scalar dispatch: the value-`if` is generated as a per-arm return chain that
 * coerces each arm to the `Int` return — the destructured `$v` (an Atom/Grounded) is
 * unwrapped to the raw primitive, the recursive `(lookup …)` already is one. No List, no
 * join. A deterministic reduction yields a single value, so the result is `1`, not `[1]`
 * (matching hyperon/PeTTa). See FunctionGenerator.generateScalarMatch / coerceForReturn.
 */
class GuardedRecursionIfTest {
    private fun repl() = ReplImpl()
    private fun String.d() = replace('_', '$')

    private val lookup = """
        (: lookup (-> Atom Atom Int))
        (= (lookup _key (Cons (Pair _k _v) _rest))
           (if (== _key _k) _v (lookup _key _rest)))
    """.trimIndent().d()

    @Test
    fun `base case at the head of the assoc list`() {
        val r = repl()
        r.eval(lookup)
        r.eval("""!(lookup a (Cons (Pair a 1) (Cons (Pair b 2) Nil)))""").let {
            assertTrue(it.isSuccess); assertEquals("1", it.result.toString())
        }
    }

    @Test
    fun `recursive case one step in`() {
        val r = repl()
        r.eval(lookup)
        r.eval("""!(lookup b (Cons (Pair a 1) (Cons (Pair b 2) Nil)))""").let {
            assertTrue(it.isSuccess); assertEquals("2", it.result.toString())
        }
    }

    @Test
    fun `recursive case deeper in`() {
        val r = repl()
        r.eval(lookup)
        r.eval("""!(lookup c (Cons (Pair a 1) (Cons (Pair c 3) (Cons (Pair d 4) Nil))))""").let {
            assertTrue(it.isSuccess); assertEquals("3", it.result.toString())
        }
    }
}
