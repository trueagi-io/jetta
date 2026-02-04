package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.runtime.space.atoms.SExpression
import net.singularity.jetta.runtime.space.atoms.toAtom
import net.singularity.jetta.runtime.space.atoms.toSAtom
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Serializes and deserializes individual index files using packed format.
 */
object IndexSerializer {
    private val MAGIC = byteArrayOf('J'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'I'.code.toByte())
    private const val VERSION: Byte = 2  // Version 2 for packed index format

    /**
     * Serialize an indexer to a file using packed format.
     *
     * Format:
     * - Magic number (4 bytes): "JTSI"
     * - Version (1 byte): 0x02
     * - Space ID (16 bytes): UUID
     * - Index ID (String)
     * - Pattern (SExpression)
     * - Variable Schema (variable count + names)
     * - Packed Matches (match count + packed bindings)
     */
    fun serialize(
        indexer: IndexerImpl,
        spaceId: UUID,
        indexId: String,
        outputPath: Path
    ) {
        val writer = BinaryWriter()

        // Write header
        MAGIC.forEach { writer.writeByte(it) }
        writer.writeByte(VERSION)

        // Write space ID
        writer.writeLong(spaceId.mostSignificantBits)
        writer.writeLong(spaceId.leastSignificantBits)

        // Write index ID
        writer.writeString(indexId)

        // Write pattern
        val sPattern = indexer.pattern.toSAtom() as SExpression
        SAtomSerializer.write(writer, sPattern)

        // Get packed index
        val packedIndex = indexer.getPackedIndex()

        // Write variable schema
        writeVariableSchema(writer, packedIndex.schema)

        // Write packed matches
        writePackedMatches(writer, packedIndex)

        Files.write(outputPath, writer.toByteArray())
    }

    /**
     * Deserialize an indexer from a file.
     */
    fun deserialize(inputPath: Path, expectedSpaceId: UUID): IndexerImpl {
        val bytes = Files.readAllBytes(inputPath)
        val reader = BinaryReader(bytes)

        // Verify magic number
        val magic = ByteArray(4) { reader.readByte() }
        if (!magic.contentEquals(MAGIC)) {
            throw IllegalArgumentException("Invalid magic number. Not a Jetta Index file.")
        }

        // Read version
        val version = reader.readByte()
        if (version != VERSION) {
            throw IllegalArgumentException("Unsupported index version: $version. Expected: $VERSION")
        }

        // Read and verify space ID
        val mostSigBits = reader.readLong()
        val leastSigBits = reader.readLong()
        val spaceId = UUID(mostSigBits, leastSigBits)

        if (spaceId != expectedSpaceId) {
            throw IllegalArgumentException("Space ID mismatch. Expected: $expectedSpaceId, got: $spaceId")
        }

        // Read index ID (for validation/logging)
        val indexId = reader.readString()

        // Read pattern
        val sPattern = SAtomSerializer.read(reader) as SExpression
        val pattern = sPattern.toAtom() as Expression

        // Create indexer
        val indexer = IndexerImpl(pattern)

        // Read variable schema
        val schema = readVariableSchema(reader)

        // Read packed matches
        val matches = readPackedMatches(reader, schema)

        // Restore packed index into indexer
        val packedMatchesField = IndexerImpl::class.java.getDeclaredField("packedMatches")
        packedMatchesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val packedMatchesList = packedMatchesField.get(indexer) as MutableList<PackedMatch>
        packedMatchesList.clear()
        packedMatchesList.addAll(matches)

        return indexer
    }

    private fun writeVariableSchema(writer: BinaryWriter, schema: VariableSchema) {
        writer.writeInt(schema.size())
        schema.variableNames.forEach { name ->
            writer.writeString(name)
        }
    }

    private fun readVariableSchema(reader: BinaryReader): VariableSchema {
        val count = reader.readInt()
        val names = List(count) { reader.readString() }
        return VariableSchema(names)
    }

    private fun writePackedMatches(writer: BinaryWriter, packedIndex: PackedIndex) {
        writer.writeInt(packedIndex.size())

        for (i in 0 until packedIndex.size()) {
            val match = packedIndex.getMatch(i)

            // Write each binding in the match
            for (varIndex in 0 until match.size()) {
                val binding = match.getBinding(varIndex)

                // Write store index
                writer.writeInt(binding.storeIndex)

                // Write atom path
                writer.writeInt(binding.atomPath.size)
                binding.atomPath.forEach { pathElement ->
                    writer.writeInt(pathElement)
                }
            }
        }
    }

    private fun readPackedMatches(reader: BinaryReader, schema: VariableSchema): List<PackedMatch> {
        val matchCount = reader.readInt()

        return List(matchCount) {
            val bindings = Array(schema.size()) {
                // Read store index
                val storeIndex = reader.readInt()

                // Read atom path
                val pathLength = reader.readInt()
                val atomPath = IntArray(pathLength) { reader.readInt() }

                PackedBinding(storeIndex, atomPath)
            }

            PackedMatch(bindings)
        }
    }
}