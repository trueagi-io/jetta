package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.Space
import net.singularity.jetta.runtime.space.SpaceImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpaceImplTest {
    fun createSpace(): Space = SpaceImpl().apply { enablePerCallBindings = false }

    private fun indexerCount(space: SpaceImpl): Int {
        val f = SpaceImpl::class.java.getDeclaredField("indexers").apply { isAccessible = true }
        return (f.get(space) as Map<*, *>).size
    }

    @Test
    fun `structurally-identical variable patterns reuse one cached indexer`() {
        // `Variable` has identity equality, so an Expression-keyed cache used to MISS on every
        // variable-bearing pattern and rebuild its index per query. The canonical key fixes
        // that: matching the same `(rel $x b)` shape twice must create exactly one indexer,
        // and a different shape must create a second.
        val space = SpaceImpl().apply { enablePerCallBindings = false }
        space.add(Expression(Symbol("rel"), Symbol("a"), Symbol("b")))
        space.add(Expression(Symbol("other"), Symbol("a"), Symbol("b")))

        space.match(Expression(Symbol("rel"), Variable("x"), Symbol("b")), Variable("x"))
        space.match(Expression(Symbol("rel"), Variable("x"), Symbol("b")), Variable("x"))
        assertEquals(1, indexerCount(space), "same variable pattern must reuse its indexer")

        space.match(Expression(Symbol("other"), Variable("x"), Symbol("b")), Variable("x"))
        assertEquals(2, indexerCount(space), "a distinct pattern gets its own indexer")
    }

    @Test
    fun `chunks with empty space`() {
        val space = createSpace()
        val chunks = space.chunks(3)

        assertEquals(3, chunks.size)
        chunks.forEach { chunk ->
            assertFalse(chunk.hasNext())
        }
    }

    @Test
    fun `chunks with single element`() {
        val space = createSpace()
        space.add(Expression(Symbol("a")))

        val chunks = space.chunks(3)

        assertEquals(3, chunks.size)
        assertTrue(chunks[0].hasNext())
        assertEquals(Symbol("a").name, (chunks[0].next().atoms[0] as Symbol).name)
        assertFalse(chunks[0].hasNext())
        assertFalse(chunks[1].hasNext())
        assertFalse(chunks[2].hasNext())
    }

    @Test
    fun `chunks divides evenly`() {
        val space = createSpace()
        repeat(6) { i ->
            space.add(Expression(Symbol("item$i")))
        }

        val chunks = space.chunks(3)

        assertEquals(3, chunks.size)
        // Each chunk should have 2 items
        chunks.forEachIndexed { index, chunk ->
            assertTrue(chunk.hasNext(), "Chunk $index should have first element")
            chunk.next()
            assertTrue(chunk.hasNext(), "Chunk $index should have second element")
            chunk.next()
            assertFalse(chunk.hasNext(), "Chunk $index should have no more elements")
        }
    }

    @Test
    fun `chunks with uneven distribution`() {
        val space = createSpace()
        repeat(10) { i ->
            space.add(Expression(Symbol("item$i")))
        }

        val chunks = space.chunks(3)

        assertEquals(3, chunks.size)

        // First chunk: 4 items
        var count = 0
        while (chunks[0].hasNext()) {
            chunks[0].next()
            count++
        }
        assertEquals(4, count)

        // Second chunk: 4 items
        count = 0
        while (chunks[1].hasNext()) {
            chunks[1].next()
            count++
        }
        assertEquals(4, count)

        // Third chunk: 2 items
        count = 0
        while (chunks[2].hasNext()) {
            chunks[2].next()
            count++
        }
        assertEquals(2, count)
    }

    @Test
    fun `chunks with more chunks than elements`() {
        val space = createSpace()
        space.add(Expression(Symbol("a")))
        space.add(Expression(Symbol("b")))

        val chunks = space.chunks(5)

        assertEquals(5, chunks.size)
        assertTrue(chunks[0].hasNext())
        assertTrue(chunks[1].hasNext())
        assertFalse(chunks[2].hasNext())
        assertFalse(chunks[3].hasNext())
        assertFalse(chunks[4].hasNext())
    }

    @Test
    fun `chunks with single chunk`() {
        val space = createSpace()
        repeat(5) { i ->
            space.add(Expression(Symbol("item$i")))
        }

        val chunks = space.chunks(1)

        assertEquals(1, chunks.size)
        var count = 0
        while (chunks[0].hasNext()) {
            chunks[0].next()
            count++
        }
        assertEquals(5, count)
    }

    @Test
    fun `chunks are independent iterators`() {
        val space = createSpace()
        repeat(4) { i ->
            space.add(Expression(Symbol("item$i")))
        }

        val chunks = space.chunks(2)

        // Iterate first chunk partially
        assertTrue(chunks[0].hasNext())
        chunks[0].next()

        // Second chunk should still be at the beginning
        assertTrue(chunks[1].hasNext())
        val firstFromSecond = chunks[1].next().atoms[0] as Symbol
        assertEquals("item2", firstFromSecond.name)
    }

    @Test
    fun `chunks with zero chunks throws exception`() {
        val space = createSpace()
        assertFailsWith<IllegalArgumentException> {
            space.chunks(0)
        }
    }

    @Test
    fun `chunks with negative chunks throws exception`() {
        val space = createSpace()
        assertFailsWith<IllegalArgumentException> {
            space.chunks(-1)
        }
    }

    @Test
    fun `match with no matches`() {
        val space = createSpace()
        space.add(Expression(Symbol("foo"), Symbol("bar")))
        space.add(Expression(Symbol("baz"), Symbol("qux")))

        val pattern = Expression(Variable("x"), Symbol("notfound"))
        val results = space.match(pattern, Variable("x"))

        assertTrue(results.isEmpty())
    }

    @Test
    fun `match simple pattern with single variable`() {
        val space = createSpace()
        space.add(Expression(Symbol("hello"), Symbol("world")))
        space.add(Expression(Symbol("hello"), Symbol("there")))
        space.add(Expression(Symbol("goodbye"), Symbol("world")))

        val pattern = Expression(Symbol("hello"), Variable("x"))
        val results = space.match(pattern, Variable("x"))

        assertEquals(2, results.size)
        assertTrue(results.any { (it as Symbol).name == "world" })
        assertTrue(results.any { (it as Symbol).name == "there" })
    }

    @Test
    fun `match pattern returns substituted expression`() {
        val space = createSpace()
        space.add(Expression(Symbol("pair"), Symbol("a"), Symbol("b")))
        space.add(Expression(Symbol("pair"), Symbol("x"), Symbol("y")))

        val pattern = Expression(Symbol("pair"), Variable("first"), Variable("second"))
        val output = Expression(Symbol("swapped"), Variable("second"), Variable("first"))
        val results = space.match(pattern, output)

        assertEquals(2, results.size)

        // Check first result: (swapped b a)
        val result1 = results.find { atom ->
            atom is Expression &&
                    (atom.atoms[1] as? Symbol)?.name == "b" &&
                    (atom.atoms[2] as? Symbol)?.name == "a"
        } as? Expression
        assertNotNull(result1)
        assertEquals("swapped", (result1.atoms[0] as Symbol).name)

        // Check second result: (swapped y x)
        val result2 = results.find { atom ->
            atom is Expression &&
                    (atom.atoms[1] as? Symbol)?.name == "y" &&
                    (atom.atoms[2] as? Symbol)?.name == "x"
        } as? Expression
        assertNotNull(result2)
        assertEquals("swapped", (result2.atoms[0] as Symbol).name)
    }

    @Test
    fun `match with nested expressions`() {
        val space = createSpace()
        space.add(
            Expression(
                Symbol("outer"),
                Expression(Symbol("inner"), Symbol("value1"))
            )
        )
        space.add(
            Expression(
                Symbol("outer"),
                Expression(Symbol("inner"), Symbol("value2"))
            )
        )

        val pattern = Expression(
            Symbol("outer"),
            Expression(Symbol("inner"), Variable("x"))
        )
        val results = space.match(pattern, Variable("x"))

        assertEquals(2, results.size)
        assertTrue(results.any { (it as Symbol).name == "value1" })
        assertTrue(results.any { (it as Symbol).name == "value2" })
    }

    @Test
    fun `match with multiple variables`() {
        val space = createSpace()
        space.add(Expression(Symbol("add"), Symbol("1"), Symbol("2")))
        space.add(Expression(Symbol("add"), Symbol("3"), Symbol("4")))
        space.add(Expression(Symbol("mul"), Symbol("5"), Symbol("6")))

        val pattern = Expression(Variable("op"), Variable("x"), Variable("y"))
        val output = Expression(
            Symbol("result"),
            Variable("op"),
            Variable("x"),
            Variable("y")
        )
        val results = space.match(pattern, output)

        assertEquals(3, results.size)

        // Verify all matches have correct structure
        results.forEach { atom ->
            val result = atom as Expression
            assertEquals("result", (result.atoms[0] as Symbol).name)
            assertEquals(4, result.atoms.size)
        }
    }

    @Test
    fun `match after mkIndex`() {
        val space = createSpace()
        space.add(Expression(Symbol("foo"), Symbol("bar")))
        space.add(Expression(Symbol("foo"), Symbol("baz")))
        space.add(Expression(Symbol("test"), Symbol("value")))

        val pattern1 = Expression(Symbol("foo"), Variable("x"))
        val pattern2 = Expression(Symbol("test"), Variable("y"))

        // Pre-index patterns
        space.mkIndex(listOf(pattern1, pattern2))

        // Match should use the pre-built index
        val results1 = space.match(pattern1, Variable("x"))
        assertEquals(2, results1.size)

        val results2 = space.match(pattern2, Variable("y"))
        assertEquals(1, results2.size)
        assertEquals("value", (results2[0] as Symbol).name)
    }

    @Test
    fun `match with same variable appearing twice`() {
        val space = createSpace()
        space.add(Expression(Symbol("eq"), Symbol("same"), Symbol("same")))
        space.add(Expression(Symbol("eq"), Symbol("a"), Symbol("b")))
        space.add(Expression(Symbol("eq"), Symbol("x"), Symbol("x")))

        val pattern = Expression(Symbol("eq"), Variable("v"), Variable("v"))
        val results = space.match(pattern, Variable("v"))

        assertEquals(2, results.size)
        assertTrue(results.any { (it as Symbol).name == "same" })
        assertTrue(results.any { (it as Symbol).name == "x" })
    }


    @Test
    fun `match with complex nested pattern`() {
        val space = createSpace()
        space.add(
            Expression(
                Expression(Symbol("leaf1"), Symbol("leaf2")),
                Symbol("leaf3")
            )
        )
        space.add(
            Expression(
                Expression(Expression(Symbol("leaf0"), Symbol("leaf1")), Symbol("leaf2")),
                Symbol("leaf3")
            )
        )

        val pattern = Expression(
            Expression(Variable("x"), Symbol("leaf2")),
            Symbol("leaf3")
        )
        val results = space.match(pattern, Variable("x"))

        assertEquals(2, results.size)
        assertTrue(results.any { (it as Symbol).name == "leaf1" })
        assertTrue(results.any {
            it is Expression &&
                    (it.atoms[0] as Symbol).name == "leaf0" &&
                    (it.atoms[1] as Symbol).name == "leaf1"
        })
    }

    @Test
    fun `match returns empty list for empty space`() {
        val space = createSpace()

        val pattern = Expression(Symbol("anything"), Variable("x"))
        val results = space.match(pattern, Variable("x"))

        assertTrue(results.isEmpty())
    }

    @Test
    fun `match with variable in operator position`() {
        val space = createSpace()
        space.add(Expression(Symbol("add"), Symbol("1"), Symbol("2")))
        space.add(Expression(Symbol("mul"), Symbol("3"), Symbol("4")))

        val pattern = Expression(Variable("op"), Symbol("1"), Symbol("2"))
        val results = space.match(pattern, Variable("op"))

        assertEquals(1, results.size)
        assertEquals("add", (results[0] as Symbol).name)
    }

    @Test
    fun `match with integer grounded values`() {
        val space = createSpace()
        space.add(Expression(Symbol("value"), Grounded(42)))
        space.add(Expression(Symbol("value"), Grounded(100)))
        space.add(Expression(Symbol("value"), Grounded(42)))

        val pattern = Expression(Symbol("value"), Variable("x"))
        val results = space.match(pattern, Variable("x"))

        assertEquals(3, results.size)
        assertTrue(results.any { (it as Grounded<*>).value == 42 })
        assertTrue(results.any { (it as Grounded<*>).value == 100 })
    }

    @Test
    fun `match with string grounded values`() {
        val space = createSpace()
        space.add(Expression(Symbol("greeting"), Grounded("hello")))
        space.add(Expression(Symbol("greeting"), Grounded("world")))
        space.add(Expression(Symbol("farewell"), Grounded("goodbye")))

        val pattern = Expression(Symbol("greeting"), Variable("msg"))
        val results = space.match(pattern, Variable("msg"))

        assertEquals(2, results.size)
        assertTrue(results.any { (it as Grounded<*>).value == "hello" })
        assertTrue(results.any { (it as Grounded<*>).value == "world" })
    }

    @Test
    fun `match with mixed grounded types`() {
        val space = createSpace()
        space.add(Expression(Symbol("data"), Grounded(42), Grounded("text")))
        space.add(Expression(Symbol("data"), Grounded(10), Grounded("hello")))
        space.add(Expression(Symbol("data"), Grounded(42), Grounded("text")))

        val pattern = Expression(Symbol("data"), Variable("num"), Variable("str"))
        val output = Expression(Symbol("pair"), Variable("str"), Variable("num"))
        val results = space.match(pattern, output)

        assertEquals(3, results.size)

        // Verify structure and values
        results.forEach { atom ->
            val result = atom as Expression
            assertEquals("pair", (result.atoms[0] as Symbol).name)
            assertTrue(result.atoms[1] is Grounded<*>)
            assertTrue(result.atoms[2] is Grounded<*>)
            assertTrue((result.atoms[1] as Grounded<*>).value is String)
            assertTrue((result.atoms[2] as Grounded<*>).value is Int)
        }
    }

    @Test
    fun `match with double grounded values`() {
        val space = createSpace()
        space.add(Expression(Symbol("measure"), Grounded(3.14)))
        space.add(Expression(Symbol("measure"), Grounded(2.71)))
        space.add(Expression(Symbol("measure"), Grounded(1.41)))

        val pattern = Expression(Symbol("measure"), Variable("x"))
        val results = space.match(pattern, Variable("x"))

        assertEquals(3, results.size)
        assertTrue(results.any { (it as Grounded<*>).value == 3.14 })
        assertTrue(results.any { (it as Grounded<*>).value == 2.71 })
        assertTrue(results.any { (it as Grounded<*>).value == 1.41 })
    }

    @Test
    fun `match with boolean grounded values`() {
        val space = createSpace()
        space.add(Expression(Symbol("flag"), Grounded(true)))
        space.add(Expression(Symbol("flag"), Grounded(false)))
        space.add(Expression(Symbol("flag"), Grounded(true)))

        val pattern = Expression(Symbol("flag"), Variable("b"))
        val results = space.match(pattern, Variable("b"))

        assertEquals(3, results.size)
        assertEquals(2, results.count { (it as Grounded<*>).value == true })
        assertEquals(1, results.count { (it as Grounded<*>).value == false })
    }

    @Test
    fun `match exact grounded value`() {
        val space = createSpace()
        space.add(Expression(Symbol("number"), Grounded(42)))
        space.add(Expression(Symbol("number"), Grounded(100)))
        space.add(Expression(Symbol("number"), Grounded(42)))

        // Pattern matches only expressions with specific grounded value
        val pattern = Expression(Symbol("number"), Grounded(42))
        val results = space.match(pattern, Symbol("found"))

        assertEquals(2, results.size)
        assertTrue(results.all { (it as Symbol).name == "found" })
    }

    @Test
    fun `match nested expression with grounded values`() {
        val space = createSpace()
        space.add(Expression(
            Symbol("calc"),
            Expression(Symbol("add"), Grounded(10), Grounded(20))
        ))
        space.add(Expression(
            Symbol("calc"),
            Expression(Symbol("mul"), Grounded(5), Grounded(6))
        ))

        val pattern = Expression(
            Symbol("calc"),
            Expression(Variable("op"), Variable("x"), Variable("y"))
        )
        val output = Expression(Symbol("result"), Variable("op"), Variable("x"), Variable("y"))
        val results = space.match(pattern, output)

        assertEquals(2, results.size)

        // First result: (result add 10 20)
        val addResult = results.find { atom ->
            atom is Expression && (atom.atoms[1] as Symbol).name == "add"
        } as Expression
        assertEquals("add", (addResult.atoms[1] as Symbol).name)
        assertEquals(10, (addResult.atoms[2] as Grounded<*>).value)
        assertEquals(20, (addResult.atoms[3] as Grounded<*>).value)
    }
}