package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.runtime.space.atoms.SExpression
import net.singularity.jetta.runtime.space.atoms.toAtom
import net.singularity.jetta.runtime.space.atoms.toSAtom
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Serializes and deserializes Space to/from a directory structure.
 */
object SpaceDirectorySerializer {

    private val MAGIC = byteArrayOf('J'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte())
    private const val VERSION: Byte = 1

    /**
     * Save a Space to a directory with custom program name.
     *
     * Creates:
     * - {directory}/{programName}.jtsf - main space store
     * - {directory}/{programName}.manifest.json - manifest
     * - {directory}/{programName}.indices/ - directory with index files
     *
     * @param space Space to serialize
     * @param directory Target directory
     * @param programName Custom program name (e.g., "MyProgram")
     * @param spaceId UUID for this space (generated if not provided)
     */
    fun save(
        space: SpaceImpl,
        directory: Path,
        programName: String = "space",
        spaceId: UUID = UUID.randomUUID()
    ) {
        // Create directory structure
        if (!directory.exists()) {
            directory.createDirectories()
        }

        val spaceFile = directory.resolve("$programName.jtsf")
        val manifestFile = directory.resolve("$programName.manifest.json")
        val indicesDir = directory.resolve("$programName.indices")
        indicesDir.createDirectories()

        // Save main space store
        saveSpaceStore(space, spaceFile, spaceId)

        // Save indices
        val indexMetadataList = saveIndices(space, indicesDir, spaceId, programName)

        // Save manifest
        val manifest = SpaceManifest(
            version = "1.0",
            spaceId = spaceId,
            created = Instant.now(),
            indices = indexMetadataList
        )
        ManifestSerializer.save(manifest, manifestFile)
    }

    /**
     * Load a Space from a directory using custom program name.
     *
     * @param directory Source directory
     * @param programName Program name used during save
     */
    fun load(directory: Path, programName: String = "space"): SpaceImpl {
        val manifestFile = directory.resolve("$programName.manifest.json")
        val spaceFile = directory.resolve("$programName.jtsf")

        // Load manifest
        val manifest = ManifestSerializer.load(manifestFile)

        // Load space store
        val space = loadSpaceStore(spaceFile, manifest.spaceId)

        // Load indices
        manifest.indices.forEach { indexMeta ->
            val indexPath = directory.resolve(indexMeta.file)
            val indexer = IndexSerializer.deserialize(indexPath, manifest.spaceId)

            // Restore indexer to space and set cached space reference
            val cachedSpaceField = IndexerImpl::class.java.getDeclaredField("cachedSpace")
            cachedSpaceField.isAccessible = true
            cachedSpaceField.set(indexer, space)

            val indexersField = SpaceImpl::class.java.getDeclaredField("indexers")
            indexersField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val indexers = indexersField.get(space) as MutableMap<Expression, IndexerImpl>
            indexers[indexer.pattern] = indexer
        }

        return space
    }

    /**
     * Save an additional index to an existing space directory.
     *
     * @param indexer Indexer to save
     * @param directory Target directory
     * @param programName Program name
     * @param indexId Custom index ID (generated if not provided)
     */
    fun saveIndex(
        indexer: IndexerImpl,
        directory: Path,
        programName: String = "space",
        indexId: String = "index-${System.currentTimeMillis()}"
    ) {
        val manifestFile = directory.resolve("$programName.manifest.json")
        val indicesDir = directory.resolve("$programName.indices")

        val manifest = ManifestSerializer.load(manifestFile)

        val indexFileName = "$indexId.jtsi"
        val indexPath = indicesDir.resolve(indexFileName)

        // Save index file
        IndexSerializer.serialize(indexer, manifest.spaceId, indexId, indexPath)

        // Get bindings count from packed index
        val packedIndex = indexer.getPackedIndex()

        // Update manifest
        val newIndexMeta = IndexMetadata(
            id = indexId,
            file = "$programName.indices/$indexFileName",
            pattern = indexer.pattern.toString(),
            created = Instant.now(),
            bindingsCount = packedIndex.size()
        )

        val updatedManifest = manifest.copy(
            indices = manifest.indices + newIndexMeta
        )

        ManifestSerializer.save(updatedManifest, manifestFile)
    }

    private fun saveSpaceStore(space: SpaceImpl, path: Path, spaceId: UUID) {
        val writer = BinaryWriter()

        // Write header
        MAGIC.forEach { writer.writeByte(it) }
        writer.writeByte(VERSION)

        // Write space ID
        writer.writeLong(spaceId.mostSignificantBits)
        writer.writeLong(spaceId.leastSignificantBits)

        // Access store via reflection
        val storeField = SpaceImpl::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val store = storeField.get(space) as List<Expression>

        // Write store
        writer.writeInt(store.size)
        store.forEach { expr ->
            val sExpr = expr.toSAtom() as SExpression
            SAtomSerializer.write(writer, sExpr)
        }

        Files.write(path, writer.toByteArray())
    }

    private fun loadSpaceStore(path: Path, expectedSpaceId: UUID): SpaceImpl {
        val bytes = Files.readAllBytes(path)
        val reader = BinaryReader(bytes)

        // Verify magic number
        val magic = ByteArray(4) { reader.readByte() }
        if (!magic.contentEquals(MAGIC)) {
            throw IllegalArgumentException("Invalid magic number. Not a Jetta Space file.")
        }

        // Verify version
        val version = reader.readByte()
        if (version != VERSION) {
            throw IllegalArgumentException("Unsupported version: $version. Expected: $VERSION")
        }

        // Read and verify space ID
        val mostSigBits = reader.readLong()
        val leastSigBits = reader.readLong()
        val spaceId = UUID(mostSigBits, leastSigBits)

        if (spaceId != expectedSpaceId) {
            throw IllegalArgumentException("Space ID mismatch. Expected: $expectedSpaceId, got: $spaceId")
        }

        val space = SpaceImpl()

        // Read store
        val storeSize = reader.readInt()
        repeat(storeSize) {
            val sExpr = SAtomSerializer.read(reader) as SExpression
            val expr = sExpr.toAtom() as Expression
            space.add(expr)
        }

        return space
    }

    private fun saveIndices(
        space: SpaceImpl,
        indicesDir: Path,
        spaceId: UUID,
        programName: String
    ): List<IndexMetadata> {
        // Access indexers via reflection
        val indexersField = SpaceImpl::class.java.getDeclaredField("indexers")
        indexersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val indexers = indexersField.get(space) as Map<Expression, IndexerImpl>

        return indexers.values.mapIndexed { index, indexer ->
            val indexId = "index-%04d".format(index + 1)
            val indexFileName = "$indexId.jtsi"
            val indexPath = indicesDir.resolve(indexFileName)

            IndexSerializer.serialize(indexer, spaceId, indexId, indexPath)

            // Get bindings count from packed index
            val packedIndex = indexer.getPackedIndex()

            IndexMetadata(
                id = indexId,
                file = "$programName.indices/$indexFileName",
                pattern = indexer.pattern.toString(),
                created = Instant.now(),
                bindingsCount = packedIndex.size()
            )
        }
    }
}