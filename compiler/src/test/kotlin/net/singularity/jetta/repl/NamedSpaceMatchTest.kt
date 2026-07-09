package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Named spaces (`&kb`, `&wuspace`, …) and the bare-variable match-all pattern.
 *
 * A `&`-prefixed reference other than `&self` denotes a space keyed by that literal name,
 * created on first use (via SpaceRegistry.getOrCreate); `add-atom`/`match`/`get-atoms` all
 * route to it exactly like `&self`. `(match &kb $x $x)` — a bare-variable pattern — matches
 * EVERY atom in the space (it can't be indexed, so JettaProgram.match binds the variable to
 * each stored atom and renders the template). Both landed to make the corpus `spaces3` pass.
 */
class NamedSpaceMatchTest {
    private fun String.d() = replace('_', '$')

    @Test
    fun `add-atom and match route to a named space`() {
        // All runs share one __main (one eval) so the space persists across them.
        ReplImpl().eval(
            """
            !(add-atom &kb (foo a))
            !(add-atom &kb (foo b))
            !(collapse (match &kb (foo _x) (foo _x)))
            """.trimIndent().d()
        ).let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals("((foo a) (foo b))", it.result.toString())
        }
    }

    @Test
    fun `match works indirectly through a variable space argument`() {
        // A match-wrapper: `$sp` is bound to a named space at the call. `match`'s space
        // parameter is Object, and JettaProgram.match resolves the Symbol to its space name,
        // so the wrapped `(match $sp …)` no longer VerifyErrors on an Atom-vs-String space.
        ReplImpl().eval(
            """
            (= (query _sp _p _r) (match _sp _p _r))
            !(add-atom &kb (foo a))
            !(add-atom &kb (foo b))
            !(collapse (query &kb (foo _x) _x))
            """.trimIndent().d()
        ).let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals("(a b)", it.result.toString())
        }
    }

    @Test
    fun `bare-variable pattern matches every atom (match-all)`() {
        ReplImpl().eval(
            """
            !(add-atom &kb (foo a))
            !(add-atom &kb (bar b))
            !(msort (collapse (match &kb _x _x)))
            """.trimIndent().d()
        ).let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals("((bar b) (foo a))", it.result.toString())
        }
    }
}
