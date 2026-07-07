package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `msort` and `once` non-determinism builtins (hyperon stdlib), used pervasively by the
 * space test programs. Both are barriers: they receive the whole collected bag.
 *  - `msort` sorts a tuple into a canonical order (so a nondeterministic bag can be compared
 *    against a literal), typically `(msort (collapse …))`.
 *  - `once` keeps only the first result of a nondeterministic computation.
 */
class MsortOnceTest {
    @Test
    fun `msort sorts a bag into canonical order`() {
        ReplImpl().eval("!(msort (superpose (3 1 2)))").let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals("(1 2 3)", it.result.toString())
        }
    }

    @Test
    fun `msort of collapse is order-independent`() {
        ReplImpl().eval("!(msort (collapse (superpose (c a b))))").let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals("(a b c)", it.result.toString())
        }
    }

    @Test
    fun `once keeps only the first result`() {
        ReplImpl().eval("!(once (superpose (a b c)))").let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals("a", it.result.toString())
        }
    }
}
