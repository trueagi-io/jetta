package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.IndexSerializer
import net.singularity.jetta.runtime.space.IndexerImpl
import net.singularity.jetta.runtime.space.SpaceImpl
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A pattern variable the match leaves UNBOUND. It happens whenever a pattern sub-term unifies
 * with a VARIABLE stored in the space: the store variable is bound to the sub-term, so the
 * sub-term's own variables stay free and no store position holds their value.
 *
 * `(bar $y)` in the space, queried by `(bar (f $z))`, binds `$y := (f $z)` and leaves `$z` free.
 * The packed match's slot for `$z` is therefore null — a normal outcome, which used to be cast to
 * a non-null element type and blew up as `NullPointerException: Parameter specified as non-null is
 * null` inside `extractAtomRaw`, one frame below the query. Two lines of MeTTa reached it, and the
 * reference stdlib's `is-function` reached it through four `unify`s.
 */
class UnboundMatchSlotTest {

    @Test
    fun `a pattern sub-term unifying with a store variable still yields its template`() {
        val space = SpaceImpl().apply { enablePerCallBindings = false }
        space.add(Expression(Symbol("bar"), Variable("y")))

        val results = space.match(
            Expression(Symbol("bar"), Expression(Symbol("f"), Variable("z"))),
            Symbol("matched"),
        )

        assertEquals(listOf(Symbol("matched")), results, "the match succeeds and renders its template")
    }

    /** The unbound variable stays a variable in the result rather than vanishing or NPE-ing. */
    @Test
    fun `the unbound pattern variable survives into the template as itself`() {
        val space = SpaceImpl().apply { enablePerCallBindings = false }
        space.add(Expression(Symbol("bar"), Variable("y")))

        val results = space.match(
            Expression(Symbol("bar"), Expression(Symbol("f"), Variable("z"))),
            Expression(Symbol("got"), Variable("z")),
        )

        assertEquals(1, results.size)
        val got = results[0] as Expression
        assertEquals(Symbol("got"), got.atoms[0])
        assertTrue(got.atoms[1] is Variable, "an unbound pattern variable is not substituted")
    }

    /** A variable the match DOES bind is still bound when a sibling slot is unbound. */
    @Test
    fun `a bound sibling variable is unaffected by an unbound slot`() {
        val space = SpaceImpl().apply { enablePerCallBindings = false }
        space.add(Expression(Symbol("bar"), Variable("y"), Symbol("tail")))

        val results = space.match(
            Expression(Symbol("bar"), Expression(Symbol("f"), Variable("z")), Variable("t")),
            Variable("t"),
        )

        assertEquals(listOf(Symbol("tail")), results)
    }

    /**
     * The `.jtsi` writer used to read `binding.storeIndex` off every slot, so an index holding an
     * unbound slot could not be written at all. The sentinel keeps the record fixed-width.
     */
    @Test
    fun `an index with an unbound slot round-trips through the packed file format`() {
        val space = SpaceImpl().apply { enablePerCallBindings = false }
        space.add(Expression(Symbol("bar"), Variable("y")))

        val pattern = Expression(Symbol("bar"), Expression(Symbol("f"), Variable("z")))
        val indexer = IndexerImpl(pattern).also { it.index(space, pattern) }
        val packed = indexer.getPackedIndex()
        assertEquals(1, packed.size(), "the pattern matches the stored fact")
        assertNull(packed.getMatch(0).getBinding(0), "the slot for the free variable is unbound")

        val spaceId = UUID.randomUUID()
        val file = Files.createTempFile("jetta-unbound-slot", ".jtsi")
        try {
            IndexSerializer.serialize(indexer, spaceId, "idx-0", file)
            val restored = IndexSerializer.deserialize(file, spaceId).getPackedIndex()

            assertEquals(1, restored.size())
            assertNull(restored.getMatch(0).getBinding(0), "the unbound slot survives the round-trip")
            assertEquals(-1, restored.getMatch(0).storeIndexOrNull(), "no store position to point at")
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
