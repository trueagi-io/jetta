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
 * Serializes and deserializes a [SpaceImpl] to/from the v2 directory layout
 * (`<name>.jtsf` + `<name>.indices/` + `<name>.manifest.json`).
 *
 * The manifest is always v2 since Track 2E. Strategy-specific extension data
 * (DeepCopy's loadModules, future Alias's aliases) is supplied by the caller.
 */
object SpaceDirectorySerializer {

    private val MAGIC = byteArrayOf('J'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte())
    private const val VERSION: Byte = 1

    /**
     * Save [space] under [programName], wrapping a v2 manifest with the supplied
     * [manifestExtension] and [strategyKind]. Default values match the legacy single-
     * module test harness: a deep-copy artefact with no transitively-loaded modules.
     */
    fun save(
        space: SpaceImpl,
        directory: Path,
        programName: String = "space",
        spaceId: UUID = UUID.randomUUID(),
        manifestExtension: ManifestExtension = ManifestExtension.DeepCopy(emptyList()),
        strategyKind: String = "deep-copy",
    ) {
        if (!directory.exists()) {
            directory.createDirectories()
        }

        val spaceFile = directory.resolve("$programName.jtsf")
        val manifestFile = directory.resolve("$programName.manifest.json")
        val indicesDir = directory.resolve("$programName.indices")
        indicesDir.createDirectories()

        saveSpaceStore(space, spaceFile, spaceId)
        val indexMetadataList = saveIndices(space, indicesDir, spaceId, programName)

        val manifest = ManifestV2(
            version = 2,
            kind = strategyKind,
            spaceId = programName,
            binaryUuid = spaceId,
            created = Instant.now(),
            indices = indexMetadataList,
            extension = manifestExtension,
        )
        ManifestSerializer.save(manifest, manifestFile)
    }

    /**
     * Load the space stored under [programName] in [directory], returning just the
     * [SpaceImpl] for callers (tests, the REPL) that don't need manifest metadata.
     * Use [loadWithManifest] for the runtime init path which also needs the
     * loadModules / aliases extension fields.
     */
    fun load(directory: Path, programName: String = "space"): SpaceImpl =
        loadWithManifest(directory, programName).first

    /**
     * Load the space + its manifest. The runtime ([JettaProgram.init]) uses this to
     * see which sibling modules need loading alongside the entry program.
     */
    fun loadWithManifest(directory: Path, programName: String = "space"): Pair<SpaceImpl, ManifestV2> {
        val manifestFile = directory.resolve("$programName.manifest.json")
        val spaceFile = directory.resolve("$programName.jtsf")

        val manifest = ManifestSerializer.load(manifestFile)
        val space = loadSpaceStore(spaceFile, manifest.binaryUuid)

        manifest.indices.forEach { indexMeta ->
            val indexPath = directory.resolve(indexMeta.file)
            val indexer = IndexSerializer.deserialize(indexPath, manifest.binaryUuid)

            val cachedSpaceField = IndexerImpl::class.java.getDeclaredField("cachedSpace")
            cachedSpaceField.isAccessible = true
            cachedSpaceField.set(indexer, space)

            val indexersField = SpaceImpl::class.java.getDeclaredField("indexers")
            indexersField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val indexers = indexersField.get(space) as MutableMap<Expression, IndexerImpl>
            indexers[indexer.pattern] = indexer
        }

        return space to manifest
    }

    /**
     * Append a single index file to an existing space directory. Re-reads the
     * manifest, adds the new index entry, and writes it back preserving every
     * other field (kind, extension, binaryUuid).
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

        IndexSerializer.serialize(indexer, manifest.binaryUuid, indexId, indexPath)

        val packedIndex = indexer.getPackedIndex()

        val newIndexMeta = IndexMetadata(
            id = indexId,
            file = "$programName.indices/$indexFileName",
            pattern = indexer.pattern.toString(),
            created = Instant.now(),
            bindingsCount = packedIndex.size()
        )

        val updatedManifest = manifest.copy(indices = manifest.indices + newIndexMeta)
        ManifestSerializer.save(updatedManifest, manifestFile)
    }

    private fun saveSpaceStore(space: SpaceImpl, path: Path, spaceId: UUID) {
        val writer = BinaryWriter()

        MAGIC.forEach { writer.writeByte(it) }
        writer.writeByte(VERSION)

        writer.writeLong(spaceId.mostSignificantBits)
        writer.writeLong(spaceId.leastSignificantBits)

        val storeField = SpaceImpl::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val store = storeField.get(space) as List<Expression>

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

        val magic = ByteArray(4) { reader.readByte() }
        if (!magic.contentEquals(MAGIC)) {
            throw IllegalArgumentException("Invalid magic number. Not a Jetta Space file.")
        }

        val version = reader.readByte()
        if (version != VERSION) {
            throw IllegalArgumentException("Unsupported version: $version. Expected: $VERSION")
        }

        val mostSigBits = reader.readLong()
        val leastSigBits = reader.readLong()
        val spaceId = UUID(mostSigBits, leastSigBits)

        if (spaceId != expectedSpaceId) {
            throw IllegalArgumentException("Space ID mismatch. Expected: $expectedSpaceId, got: $spaceId")
        }

        val space = SpaceImpl()

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
        val indexersField = SpaceImpl::class.java.getDeclaredField("indexers")
        indexersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val indexers = indexersField.get(space) as Map<Expression, IndexerImpl>

        return indexers.values.mapIndexed { index, indexer ->
            val indexId = "index-%04d".format(index + 1)
            val indexFileName = "$indexId.jtsi"
            val indexPath = indicesDir.resolve(indexFileName)

            IndexSerializer.serialize(indexer, spaceId, indexId, indexPath)

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
