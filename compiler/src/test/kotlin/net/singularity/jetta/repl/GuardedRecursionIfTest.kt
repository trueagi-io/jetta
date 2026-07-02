package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A multivalued (`List`-returning) function whose body is an `if` with a HETEROGENEOUS
 * pair of arms — a scalar destructured value on one side, a recursive multivalued call
 * on the other, e.g. `(if (== _key _k) _v (lookup _key _rest))`.
 *
 * Such an `if` must uphold the invariant "a multivalued `-> Int` function is physically
 * `List<Int>`": both arms yield a List and the singleton scalar arm carries the same
 * element type as the recursive arm. Before the fix the `if` was left heterogeneous —
 * one arm a scalar Atom, the other a `List` — so `generateMatchBranch` coerced a `List`
 * as if it were a single `Grounded` (ClassCastException), and once seq-wrapped the scalar
 * arm leaked a raw `Grounded` element the consuming `map?` lambda could not unbox
 * (Grounded→Number ClassCastException). See CanonicalFormRewriter.rewriteIf +
 * Context IF re-resolve + FunctionGenerator.generateSeq.
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
            assertTrue(it.isSuccess); assertEquals("[1]", it.result.toString())
        }
    }

    @Test
    fun `recursive case one step in`() {
        val r = repl()
        r.eval(lookup)
        r.eval("""!(lookup b (Cons (Pair a 1) (Cons (Pair b 2) Nil)))""").let {
            assertTrue(it.isSuccess); assertEquals("[2]", it.result.toString())
        }
    }

    @Test
    fun `recursive case deeper in`() {
        val r = repl()
        r.eval(lookup)
        r.eval("""!(lookup c (Cons (Pair a 1) (Cons (Pair c 3) (Cons (Pair d 4) Nil))))""").let {
            assertTrue(it.isSuccess); assertEquals("[3]", it.result.toString())
        }
    }
}
