package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.*
import net.singularity.jetta.runtime.space.atoms.*
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.*

class SpaceDirectorySerializerTest {

    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("jetta-test")
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `save and load empty space with default name`() {
        val space = SpaceImpl()

        SpaceDirectorySerializer.save(space, tempDir)

        // Verify files exist
        assertTrue(tempDir.resolve("space.jtsf").toFile().exists())
        assertTrue(tempDir.resolve("space.manifest.json").toFile().exists())
        assertTrue(tempDir.resolve("space.indices").toFile().exists())

        val loaded = SpaceDirectorySerializer.load(tempDir)
        assertNotNull(loaded)
    }

    @Test
    fun `save and load empty space with custom name`() {
        val space = SpaceImpl()

        SpaceDirectorySerializer.save(space, tempDir, programName = "MyProgram")

        // Verify files exist with custom names
        assertTrue(tempDir.resolve("MyProgram.jtsf").toFile().exists())
        assertTrue(tempDir.resolve("MyProgram.manifest.json").toFile().exists())
        assertTrue(tempDir.resolve("MyProgram.indices").toFile().exists())

        val loaded = SpaceDirectorySerializer.load(tempDir, programName = "MyProgram")
        assertNotNull(loaded)
    }

    @Test
    fun `save and load space with simple expressions`() {
        val space = SpaceImpl()
        space.add(Expression(Symbol("hello"), Symbol("world")))
        space.add(Expression(Symbol("foo"), Symbol("bar")))

        SpaceDirectorySerializer.save(space, tempDir, programName = "Test")
        val loaded = SpaceDirectorySerializer.load(tempDir, programName = "Test")

        // Verify store content
        val storeField = SpaceImpl::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val loadedStore = storeField.get(loaded) as List<Expression>

        assertEquals(2, loadedStore.size)
        assertEquals("hello", (loadedStore[0].atoms[0] as Symbol).name)
        assertEquals("world", (loadedStore[0].atoms[1] as Symbol).name)
        assertEquals("foo", (loadedStore[1].atoms[0] as Symbol).name)
        assertEquals("bar", (loadedStore[1].atoms[1] as Symbol).name)
    }

    @Test
    fun `save and load space with nested expressions`() {
        val space = SpaceImpl()
        space.add(
            Expression(
                Symbol("outer"),
                Expression(
                    Symbol("inner"),
                    Symbol("value")
                )
            )
        )

        SpaceDirectorySerializer.save(space, tempDir, programName = "Nested")
        val loaded = SpaceDirectorySerializer.load(tempDir, programName = "Nested")

        val storeField = SpaceImpl::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val loadedStore = storeField.get(loaded) as List<Expression>

        assertEquals(1, loadedStore.size)
        val expr = loadedStore[0]
        assertEquals(2, expr.atoms.size)
        val nested = expr.atoms[1] as Expression
        assertEquals("inner", (nested.atoms[0] as Symbol).name)
        assertEquals("value", (nested.atoms[1] as Symbol).name)
    }

    @Test
    fun `save and load space with all grounded types`() {
        val space = SpaceImpl()
        space.add(Expression(Symbol("int"), Grounded(42)))
        space.add(Expression(Symbol("long"), Grounded(9876543210L)))
        space.add(Expression(Symbol("double"), Grounded(3.14159)))
        space.add(Expression(Symbol("string"), Grounded("hello world")))
        space.add(Expression(Symbol("boolean"), Grounded(true)))

        SpaceDirectorySerializer.save(space, tempDir, programName = "Types")
        val loaded = SpaceDirectorySerializer.load(tempDir, programName = "Types")

        val storeField = SpaceImpl::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val loadedStore = storeField.get(loaded) as List<Expression>

        assertEquals(5, loadedStore.size)
        assertEquals(42, (loadedStore[0].atoms[1] as Grounded<*>).value)
        assertEquals(9876543210L, (loadedStore[1].atoms[1] as Grounded<*>).value)
        assertEquals(3.14159, (loadedStore[2].atoms[1] as Grounded<*>).value)
        assertEquals("hello world", (loadedStore[3].atoms[1] as Grounded<*>).value)
        assertEquals(true, (loadedStore[4].atoms[1] as Grounded<*>).value)
    }

    @Test
    fun `save and load space with indexers`() {
        val space = SpaceImpl()
        space.add(Expression(Symbol("foo"), Symbol("bar")))
        space.add(Expression(Symbol("foo"), Symbol("baz")))
        space.add(Expression(Symbol("other"), Symbol("thing")))

        val pattern = Expression(Symbol("foo"), Variable("x"))
        space.mkIndex(listOf(pattern))

        SpaceDirectorySerializer.save(space, tempDir, programName = "Indexed")

        // Verify index file was created
        val indicesDir = tempDir.resolve("Indexed.indices")
        assertTrue(indicesDir.toFile().exists())
        assertTrue(indicesDir.resolve("index-0001.jtsi").toFile().exists())

        val loaded = SpaceDirectorySerializer.load(tempDir, programName = "Indexed")

        // Verify indexers were restored
        val indexersField = SpaceImpl::class.java.getDeclaredField("indexers")
        indexersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val indexers = indexersField.get(loaded) as Map<Expression, IndexerImpl>

        assertEquals(1, indexers.size)

        // Verify packed matches were restored
        val indexer = indexers.values.first()
        val packedIndex = indexer.getPackedIndex()

        assertEquals(2, packedIndex.size())

        // Verify we can resolve the bindings
        val allBindings = packedIndex.resolveAll(loaded)
        assertEquals(2, allBindings.size)
    }

    @Test
    fun `save multiple programs in same directory`() {
        val space1 = SpaceImpl()
        space1.add(Expression(Symbol("program1"), Symbol("data")))

        val space2 = SpaceImpl()
        space2.add(Expression(Symbol("program2"), Symbol("data")))

        SpaceDirectorySerializer.save(space1, tempDir, programName = "Program1")
        SpaceDirectorySerializer.save(space2, tempDir, programName = "Program2")

        // Verify both sets of files exist
        assertTrue(tempDir.resolve("Program1.jtsf").toFile().exists())
        assertTrue(tempDir.resolve("Program2.jtsf").toFile().exists())

        val loaded1 = SpaceDirectorySerializer.load(tempDir, programName = "Program1")
        val loaded2 = SpaceDirectorySerializer.load(tempDir, programName = "Program2")

        assertNotNull(loaded1)
        assertNotNull(loaded2)
    }

    @Test
    fun `save additional index to existing space`() {
        val space = SpaceImpl()
        space.add(Expression(Symbol("foo"), Symbol("bar")))
        space.add(Expression(Symbol("test"), Symbol("value")))

        val pattern1 = Expression(Symbol("foo"), Variable("x"))
        space.mkIndex(listOf(pattern1))

        SpaceDirectorySerializer.save(space, tempDir, programName = "Dynamic")

        // Create and save additional index
        val pattern2 = Expression(Symbol("test"), Variable("y"))
        val indexer2 = IndexerImpl(pattern2)
        indexer2.index(space, pattern2)

        SpaceDirectorySerializer.saveIndex(
            indexer2,
            tempDir,
            programName = "Dynamic",
            indexId = "custom-index"
        )

        // Verify new index file exists
        assertTrue(tempDir.resolve("Dynamic.indices/custom-index.jtsi").toFile().exists())

        // Load and verify both indices
        val loaded = SpaceDirectorySerializer.load(tempDir, programName = "Dynamic")

        val indexersField = SpaceImpl::class.java.getDeclaredField("indexers")
        indexersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val indexers = indexersField.get(loaded) as Map<Expression, IndexerImpl>

        assertEquals(2, indexers.size)
    }

    @Test
    fun `large space serialization`() {
        val space = SpaceImpl()
        repeat(1000) { i ->
            space.add(Expression(Symbol("item$i"), Grounded(i), Grounded("value$i")))
        }

        SpaceDirectorySerializer.save(space, tempDir, programName = "Large")
        val loaded = SpaceDirectorySerializer.load(tempDir, programName = "Large")

        val storeField = SpaceImpl::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val loadedStore = storeField.get(loaded) as List<Expression>

        assertEquals(1000, loadedStore.size)
        assertEquals("item500", (loadedStore[500].atoms[0] as Symbol).name)
        assertEquals(500, (loadedStore[500].atoms[1] as Grounded<*>).value)
    }

    @Test
    fun `match functionality after deserialization`() {
        val space = SpaceImpl()
        space.add(Expression(Symbol("add"), Grounded(1), Grounded(2)))
        space.add(Expression(Symbol("add"), Grounded(3), Grounded(4)))
        space.add(Expression(Symbol("mul"), Grounded(5), Grounded(6)))

        SpaceDirectorySerializer.save(space, tempDir, programName = "Match")
        val loaded = SpaceDirectorySerializer.load(tempDir, programName = "Match")

        // Test matching
        val pattern = Expression(Symbol("add"), Variable("x"), Variable("y"))
        val results = loaded.match(pattern, Variable("x"))

        assertEquals(2, results.size)
    }

    @Test
    fun `space ID consistency`() {
        val space = SpaceImpl()
        space.add(Expression(Symbol("test"), Symbol("data")))

        val spaceId = UUID.randomUUID()
        SpaceDirectorySerializer.save(space, tempDir, programName = "IDTest", spaceId = spaceId)

        // Manifest v2: programName ends up in `spaceId` (the registry key); the random
        // UUID we passed in lives under `binaryUuid` (the .jtsf↔manifest pairing token).
        val manifest = ManifestSerializer.load(tempDir.resolve("IDTest.manifest.json"))
        assertEquals(spaceId, manifest.binaryUuid)
        assertEquals("IDTest", manifest.spaceId)
        assertEquals("deep-copy", manifest.kind)
    }
}

class BinaryWriterReaderTest {

    @Test
    fun `write and read byte`() {
        val writer = BinaryWriter()
        writer.writeByte(42)
        writer.writeByte(-1)
        writer.writeByte(127)

        val reader = BinaryReader(writer.toByteArray())
        assertEquals(42.toByte(), reader.readByte())
        assertEquals((-1).toByte(), reader.readByte())
        assertEquals(127.toByte(), reader.readByte())
    }

    @Test
    fun `write and read various integers`() {
        val writer = BinaryWriter()
        val testValues = listOf(0, 1, 127, 128, 255, 256, 16383, 16384, 65535, 65536, Int.MAX_VALUE, Int.MIN_VALUE, -1)

        testValues.forEach { writer.writeInt(it) }

        val reader = BinaryReader(writer.toByteArray())
        testValues.forEach { expected ->
            assertEquals(expected, reader.readInt(), "Failed for value: $expected")
        }
    }

    @Test
    fun `write and read various longs`() {
        val writer = BinaryWriter()
        val testValues = listOf(0L, 127L, 128L, 65535L, 4294967295L, Long.MAX_VALUE, Long.MIN_VALUE, -1L)

        testValues.forEach { writer.writeLong(it) }

        val reader = BinaryReader(writer.toByteArray())
        testValues.forEach { expected ->
            assertEquals(expected, reader.readLong(), "Failed for value: $expected")
        }
    }

    @Test
    fun `write and read strings`() {
        val writer = BinaryWriter()
        writer.writeString("")
        writer.writeString("hello")
        writer.writeString("Hello, World!")
        writer.writeString("Unicode: 你好世界 🚀")
        writer.writeString("Special chars: \n\t\r")

        val reader = BinaryReader(writer.toByteArray())
        assertEquals("", reader.readString())
        assertEquals("hello", reader.readString())
        assertEquals("Hello, World!", reader.readString())
        assertEquals("Unicode: 你好世界 🚀", reader.readString())
        assertEquals("Special chars: \n\t\r", reader.readString())
    }

    @Test
    fun `write and read booleans`() {
        val writer = BinaryWriter()
        writer.writeBoolean(true)
        writer.writeBoolean(false)
        writer.writeBoolean(true)

        val reader = BinaryReader(writer.toByteArray())
        assertTrue(reader.readBoolean())
        assertFalse(reader.readBoolean())
        assertTrue(reader.readBoolean())
    }

    @Test
    fun `write and read doubles`() {
        val writer = BinaryWriter()
        writer.writeDouble(0.0)
        writer.writeDouble(3.14159)
        writer.writeDouble(-273.15)
        writer.writeDouble(Double.MAX_VALUE)
        writer.writeDouble(Double.MIN_VALUE)
        writer.writeDouble(Double.POSITIVE_INFINITY)
        writer.writeDouble(Double.NEGATIVE_INFINITY)

        val reader = BinaryReader(writer.toByteArray())
        assertEquals(0.0, reader.readDouble())
        assertEquals(3.14159, reader.readDouble())
        assertEquals(-273.15, reader.readDouble())
        assertEquals(Double.MAX_VALUE, reader.readDouble())
        assertEquals(Double.MIN_VALUE, reader.readDouble())
        assertEquals(Double.POSITIVE_INFINITY, reader.readDouble())
        assertEquals(Double.NEGATIVE_INFINITY, reader.readDouble())
    }

    @Test
    fun `hasMore functionality`() {
        val writer = BinaryWriter()
        writer.writeInt(42)
        writer.writeInt(100)

        val reader = BinaryReader(writer.toByteArray())
        assertTrue(reader.hasMore())
        reader.readInt()
        assertTrue(reader.hasMore())
        reader.readInt()
        assertFalse(reader.hasMore())
    }

    @Test
    fun `reading beyond stream throws exception`() {
        val reader = BinaryReader(byteArrayOf())
        assertFailsWith<IllegalStateException> {
            reader.readByte()
        }
    }

    @Test
    fun `mixed type serialization`() {
        val writer = BinaryWriter()
        writer.writeInt(42)
        writer.writeString("test")
        writer.writeBoolean(true)
        writer.writeDouble(3.14)
        writer.writeLong(9876543210L)

        val reader = BinaryReader(writer.toByteArray())
        assertEquals(42, reader.readInt())
        assertEquals("test", reader.readString())
        assertTrue(reader.readBoolean())
        assertEquals(3.14, reader.readDouble())
        assertEquals(9876543210L, reader.readLong())
    }
}

class SAtomSerializerTest {

    @Test
    fun `serialize and deserialize SSymbol`() {
        val writer = BinaryWriter()
        SAtomSerializer.write(writer, SSymbol("hello"))

        val reader = BinaryReader(writer.toByteArray())
        val restored = SAtomSerializer.read(reader) as SSymbol

        assertEquals("hello", restored.name)
    }

    @Test
    fun `serialize and deserialize flat SExpression`() {
        val writer = BinaryWriter()
        val expr = SExpression(listOf(SSymbol("foo"), SSymbol("bar"), SSymbol("baz")))

        SAtomSerializer.write(writer, expr)

        val reader = BinaryReader(writer.toByteArray())
        val restored = SAtomSerializer.read(reader) as SExpression

        assertEquals(3, restored.atoms.size)
        assertEquals("foo", (restored.atoms[0] as SSymbol).name)
        assertEquals("bar", (restored.atoms[1] as SSymbol).name)
        assertEquals("baz", (restored.atoms[2] as SSymbol).name)
    }

    @Test
    fun `serialize and deserialize nested SExpression`() {
        val writer = BinaryWriter()
        val expr = SExpression(
            listOf(
                SSymbol("outer"),
                SExpression(
                    listOf(
                        SSymbol("inner"),
                        SExpression(listOf(SSymbol("deep")))
                    )
                )
            )
        )

        SAtomSerializer.write(writer, expr)

        val reader = BinaryReader(writer.toByteArray())
        val restored = SAtomSerializer.read(reader) as SExpression

        assertEquals(2, restored.atoms.size)
        val nested = restored.atoms[1] as SExpression
        assertEquals(2, nested.atoms.size)
        val deep = nested.atoms[1] as SExpression
        assertEquals(1, deep.atoms.size)
        assertEquals("deep", (deep.atoms[0] as SSymbol).name)
    }

    @Test
    fun `serialize and deserialize all SGrounded types`() {
        val writer = BinaryWriter()

        SAtomSerializer.write(writer, SGrounded(42))
        SAtomSerializer.write(writer, SGrounded(42L))
        SAtomSerializer.write(writer, SGrounded(3.14))
        SAtomSerializer.write(writer, SGrounded("test"))
        SAtomSerializer.write(writer, SGrounded(true))
        SAtomSerializer.write(writer, SGrounded(false))

        val reader = BinaryReader(writer.toByteArray())

        assertEquals(42, (SAtomSerializer.read(reader) as SGrounded<*>).value)
        assertEquals(42L, (SAtomSerializer.read(reader) as SGrounded<*>).value)
        assertEquals(3.14, (SAtomSerializer.read(reader) as SGrounded<*>).value)
        assertEquals("test", (SAtomSerializer.read(reader) as SGrounded<*>).value)
        assertEquals(true, (SAtomSerializer.read(reader) as SGrounded<*>).value)
        assertEquals(false, (SAtomSerializer.read(reader) as SGrounded<*>).value)
    }

    @Test
    fun `serialize complex structure`() {
        val writer = BinaryWriter()
        val complex = SExpression(
            listOf(
                SSymbol("function"),
                SGrounded("name"),
                SExpression(
                    listOf(
                        SSymbol("params"),
                        SGrounded(10),
                        SGrounded(20.5),
                        SGrounded(true)
                    )
                ),
                SSymbol("end")
            )
        )

        SAtomSerializer.write(writer, complex)

        val reader = BinaryReader(writer.toByteArray())
        val restored = SAtomSerializer.read(reader) as SExpression

        assertEquals(4, restored.atoms.size)
        assertEquals("function", (restored.atoms[0] as SSymbol).name)
        assertEquals("name", (restored.atoms[1] as SGrounded<*>).value)

        val params = restored.atoms[2] as SExpression
        assertEquals(4, params.atoms.size)
        assertEquals("params", (params.atoms[0] as SSymbol).name)
        assertEquals(10, (params.atoms[1] as SGrounded<*>).value)
        assertEquals(20.5, (params.atoms[2] as SGrounded<*>).value)
        assertEquals(true, (params.atoms[3] as SGrounded<*>).value)

        assertEquals("end", (restored.atoms[3] as SSymbol).name)
    }

    @Test
    fun `empty SExpression`() {
        val writer = BinaryWriter()
        SAtomSerializer.write(writer, SExpression(emptyList()))

        val reader = BinaryReader(writer.toByteArray())
        val restored = SAtomSerializer.read(reader) as SExpression

        assertEquals(0, restored.atoms.size)
    }
}