package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mutable state cells (hyperon `new-state`/`get-state`/`change-state!` + `bind!`).
 * `new-state` makes a cell, `bind!` names it via a token, `get-state` reads it and
 * `change-state!` mutates it in place. All runs share one __main (one eval) so the token
 * binding and the cell persist across them.
 */
class StateTest {
    @Test
    fun `get-state reads a bound new-state`() {
        ReplImpl().eval(
            """
            !(bind! s (new-state rest))
            !(get-state s)
            """.trimIndent()
        ).let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals("rest", it.result.toString())
        }
    }

    @Test
    fun `change-state! mutates the cell in place`() {
        ReplImpl().eval(
            """
            !(bind! s (new-state rest))
            !(change-state! s active)
            !(get-state s)
            """.trimIndent()
        ).let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals("active", it.result.toString())
        }
    }
}
