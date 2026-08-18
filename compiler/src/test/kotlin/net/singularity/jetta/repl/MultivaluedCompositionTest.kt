package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Composing a MULTIVALUED (`List`-returning) call into a driver that consumes it:
 *  - a non-determinism BARRIER (`assertEqual` / `assertEqualToResult`) receives the whole
 *    bag as an argument. Its parameter is `Object`, so the `List` is a valid value — but
 *    codegen used to box the call result as its scalar element `type` (`Integer.valueOf`
 *    over a `List` reference → VerifyError). Now a multivalued call is left unboxed.
 *  - `println` (not a barrier) lifts the multivalued arg into a `map?` whose lambda body
 *    is a void/Unit call; the lambda now returns a reference (ANY) so it ARETURNs the null
 *    placeholder instead of IRETURNing it (VerifyError).
 *
 * Guards against regressing either VerifyError. See FunctionGenerator (skip boxing for a
 * multivalued call) + Context MAP_ re-resolve (Unit body → ANY return).
 */
class MultivaluedCompositionTest {
    private fun repl() = ReplImpl()
    private fun String.d() = replace('_', '$')

    private val lookup = """
        (: lookup (-> Atom Atom Int))
        (= (lookup _key (Cons (Pair _k _v) _rest))
           (if (== _key _k) _v (lookup _key _rest)))
    """.trimIndent().d()

    @Test
    fun `assertEqual over a superpose-based multivalued call`() {
        val r = repl()
        r.eval("(@ pick multivalued)\n(: pick (-> Int))\n(= (pick) (superpose (2)))".d())
        r.eval("""!(assertEqual (pick) 2)""").let { assertTrue(it.isSuccess, it.messages.joinToString("\n")) }
    }

    @Test
    fun `assertEqualToResult over a multivalued call compares the whole bag`() {
        val r = repl()
        r.eval("(@ pick multivalued)\n(: pick (-> Int))\n(= (pick) (superpose (5 6 7)))".d())
        r.eval("""!(assertEqualToResult (pick) (5 6 7))""").let { assertTrue(it.isSuccess, it.messages.joinToString("\n")) }
    }

    @Test
    fun `assertEqual over a guarded-recursion multivalued function`() {
        val r = repl()
        r.eval(lookup)
        r.eval("""!(assertEqual (lookup b (Cons (Pair a 1) (Cons (Pair b 2) Nil))) 2)""").let {
            assertTrue(it.isSuccess, it.messages.joinToString("\n"))
        }
    }

    @Test
    fun `println over a guarded-recursion multivalued function does not VerifyError`() {
        val r = repl()
        r.eval(lookup)
        r.eval("""!(println! (lookup b (Cons (Pair a 1) (Cons (Pair b 2) Nil))))""").let {
            assertTrue(it.isSuccess, it.messages.joinToString("\n"))
        }
    }
}
