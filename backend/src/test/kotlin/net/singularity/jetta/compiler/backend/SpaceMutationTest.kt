package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Space mutation built-ins: `add-atom`, `remove-atom`, `get-atoms` over `&self`.
 *
 * The load-bearing property is that a mutation is immediately visible to a later `match`
 * — including when that pattern's indexer was already built and cached by an EARLIER
 * match. `SpaceImpl.add` folds the new atom into every cached indexer incrementally
 * (IndexerImpl.indexOne), so the packed index stays live under runtime mutation instead
 * of going stale (which a bare `store.add` would have caused).
 */
class SpaceMutationTest : GeneratorTestBase() {
    private fun program() = """
        (Parent Tom Bob)

        (= (allpairs) (match &self (Parent ${'$'}a ${'$'}b) (${'$'}a ${'$'}b)))
        (= (kids ${'$'}p) (match &self (Parent ${'$'}p ${'$'}k) ${'$'}k))
        (= (addfact ${'$'}p ${'$'}k) (add-atom &self (Parent ${'$'}p ${'$'}k)))
        (= (dropfact ${'$'}p ${'$'}k) (remove-atom &self (Parent ${'$'}p ${'$'}k)))
        (= (everything) (get-atoms &self))
    """.trimIndent()

    @Test
    fun `add-atom is visible to a match whose indexer was already cached`() {
        compile("SpaceMut.metta", program(), mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, mc) ->
                assertTrue(mc.list().isEmpty(), mc.list().toString())
                val cls = result.toMap().toClasses()["SpaceMut"]!!
                JettaProgram.init("SpaceMut")

                val allpairs = cls.getMethod("allpairs")
                val addfact = cls.getMethod("addfact", Atom::class.java, Atom::class.java)

                // First match builds and caches the indexer for (Parent $a $b) — sees only
                // the compiled fact.
                (allpairs.invoke(null) as List<*>).let {
                    assertEquals(setOf("(Tom Bob)"), it.map { a -> a.toString() }.toSet())
                }

                // Mutate: add a second fact.
                addfact.invoke(null, Symbol("Bob"), Symbol("Ann"))

                // Re-run the SAME pattern: the cached indexer must now contain the new atom.
                (allpairs.invoke(null) as List<*>).let {
                    assertEquals(setOf("(Tom Bob)", "(Bob Ann)"), it.map { a -> a.toString() }.toSet())
                }
            }
    }

    @Test
    fun `add-atom then a fresh pattern match finds the new atom`() {
        compile("SpaceMut2.metta", program(), mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, mc) ->
                assertTrue(mc.list().isEmpty(), mc.list().toString())
                val cls = result.toMap().toClasses()["SpaceMut2"]!!
                JettaProgram.init("SpaceMut2")

                val kids = cls.getMethod("kids", Atom::class.java)
                val addfact = cls.getMethod("addfact", Atom::class.java, Atom::class.java)

                addfact.invoke(null, Symbol("Bob"), Symbol("Ann"))
                // A pattern never matched before — its indexer is built fresh from the store,
                // which already includes the added atom.
                (kids.invoke(null, Symbol("Bob")) as List<*>).let {
                    assertEquals(listOf("Ann"), it.map { a -> a.toString() })
                }
                // The compiled fact still resolves too.
                (kids.invoke(null, Symbol("Tom")) as List<*>).let {
                    assertEquals(listOf("Bob"), it.map { a -> a.toString() })
                }
            }
    }

    @Test
    fun `remove-atom drops the fact from subsequent matches`() {
        compile("SpaceMut3.metta", program(), mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, mc) ->
                assertTrue(mc.list().isEmpty(), mc.list().toString())
                val cls = result.toMap().toClasses()["SpaceMut3"]!!
                JettaProgram.init("SpaceMut3")

                val allpairs = cls.getMethod("allpairs")
                val addfact = cls.getMethod("addfact", Atom::class.java, Atom::class.java)
                val dropfact = cls.getMethod("dropfact", Atom::class.java, Atom::class.java)

                addfact.invoke(null, Symbol("Bob"), Symbol("Ann"))
                assertEquals(2, (allpairs.invoke(null) as List<*>).size)

                dropfact.invoke(null, Symbol("Tom"), Symbol("Bob"))
                (allpairs.invoke(null) as List<*>).let {
                    assertEquals(setOf("(Bob Ann)"), it.map { a -> a.toString() }.toSet())
                }
            }
    }

    @Test
    fun `get-atoms returns the full space bag including mutations`() {
        compile("SpaceMut4.metta", program(), mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, mc) ->
                assertTrue(mc.list().isEmpty(), mc.list().toString())
                val cls = result.toMap().toClasses()["SpaceMut4"]!!
                JettaProgram.init("SpaceMut4")

                val everything = cls.getMethod("everything")
                val addfact = cls.getMethod("addfact", Atom::class.java, Atom::class.java)

                // get-atoms returns the WHOLE space — the fact plus the `=` rule atoms
                // (equality definitions live in the space too, matching hyperon), so assert
                // membership and the delta rather than an exact set.
                val before = (everything.invoke(null) as List<*>).map { it.toString() }
                assertTrue("(Parent Tom Bob)" in before, "expected the fact in $before")
                assertTrue(before.none { it == "(Parent Bob Ann)" })

                addfact.invoke(null, Symbol("Bob"), Symbol("Ann"))

                val after = (everything.invoke(null) as List<*>).map { it.toString() }
                assertTrue("(Parent Bob Ann)" in after, "added atom should appear in $after")
                assertEquals(before.size + 1, after.size, "get-atoms should grow by exactly one")
            }
    }

    /**
     * The space may arrive INDIRECTLY — as the value of a variable rather than a literal `&name`
     * the compiler lowers to a String. `match` was given that treatment already; `add-atom`,
     * `remove-atom` and `get-atoms` kept a `String` parameter, so an `Atom` reaching one of them
     * was rejected at CLASS LOAD and took the whole class with it. That is how the reference
     * stdlib is written throughout: `(= (add-reduct $dst $atom) (add-atom $dst $atom))`.
     */
    @Test
    fun `a space passed as an argument reaches add-atom, remove-atom and get-atoms`() {
        val code = """
            (Parent Tom Bob)
            (= (addto ${'$'}s ${'$'}a) (add-atom ${'$'}s ${'$'}a))
            (= (dropfrom ${'$'}s ${'$'}a) (remove-atom ${'$'}s ${'$'}a))
            (= (countof ${'$'}s) (get-atoms ${'$'}s))
            (= (kids ${'$'}p) (match &self (Parent ${'$'}p ${'$'}k) ${'$'}k))
        """.trimIndent()
        compile("SpaceIndirect.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, mc) ->
                assertTrue(mc.list().isEmpty(), mc.list().toString())
                val cls = result.toMap().toClasses()["SpaceIndirect"]!!
                JettaProgram.init("SpaceIndirect")

                val addto = cls.getMethod("addto", Any::class.java, Atom::class.java)
                val dropfrom = cls.getMethod("dropfrom", Any::class.java, Atom::class.java)
                val countof = cls.getMethod("countof", Any::class.java)
                val kids = cls.getMethod("kids", Atom::class.java)
                val self = Symbol("&self")

                val before = (countof.invoke(null, self) as List<*>).size
                addto.invoke(null, self, expr("Parent", "Bob", "Ann"))
                assertEquals(
                    listOf("Ann"),
                    (kids.invoke(null, Symbol("Bob")) as List<*>).map { it.toString() },
                    "the atom added through a variable-held space must be matchable"
                )
                assertEquals(before + 1, (countof.invoke(null, self) as List<*>).size)

                dropfrom.invoke(null, self, expr("Parent", "Bob", "Ann"))
                assertEquals(before, (countof.invoke(null, self) as List<*>).size)
            }
    }

    private fun expr(vararg names: String): Atom =
        net.singularity.jetta.compiler.frontend.ir.Expression(names.map { Symbol(it) })
}
