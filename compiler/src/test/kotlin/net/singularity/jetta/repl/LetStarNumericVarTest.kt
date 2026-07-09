package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two frontend unblocks the MeTTa compatibility corpus needs pervasively:
 *  - all-numeric variable names (`$1`, `$2`) — the grammar's `variable` rule now accepts
 *    `$` + INTEGER, and `*` is a legal identifier character so `let*` lexes as one symbol;
 *  - `let*` sequential bindings, desugared to right-nested `let`s (each binding sees the
 *    earlier ones), then to lambda applications by the existing `let` path.
 */
class LetStarNumericVarTest {
    private fun String.d() = replace('_', '$')

    @Test
    fun `let star bindings are sequential`() {
        // $b sees $a — sequential (let*), not parallel.
        ReplImpl().eval("!(let* ((_a 1) (_b (+ _a 1))) (+ _a _b))".d()).let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals(3, it.result) // 1 + 2
        }
    }

    @Test
    fun `numeric variable names parse and bind`() {
        val r = ReplImpl()
        r.eval("(= (hd (_1 _2)) _1)".d())
        r.eval("!(hd (7 8))".d()).let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals("7", it.result.toString())
        }
    }

    @Test
    fun `let star over a numeric-var match template composes`() {
        // Mirrors the corpus `spaces` shape: let* binds a side effect, then the body uses
        // a `$1` match template. Here without a space, just exercising parse + let* + $1.
        ReplImpl().eval("!(let* ((_1 10) (_2 (+ _1 5))) (+ _1 _2))".d()).let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals(25, it.result) // 10 + 15
        }
    }
}
