package net.singularity.jetta.runtime.space

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Handles serialization of the manifest.json file.
 */
object ManifestSerializer {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class IndexMetadataDto(
        val id: String,
        val file: String,
        val pattern: String,
        val created: String,
        val bindingsCount: Int
    )

    @Serializable
    private data class SpaceManifestDto(
        val version: String,
        val spaceId: String,
        val created: String,
        val indices: List<IndexMetadataDto>
    )

    fun save(manifest: SpaceManifest, path: Path) {
        val dto = SpaceManifestDto(
            version = manifest.version,
            spaceId = manifest.spaceId.toString(),
            created = manifest.created.toString(),
            indices = manifest.indices.map { idx ->
                IndexMetadataDto(
                    id = idx.id,
                    file = idx.file,
                    pattern = idx.pattern,
                    created = idx.created.toString(),
                    bindingsCount = idx.bindingsCount
                )
            }
        )

        val jsonString = json.encodeToString(dto)
        Files.writeString(path, jsonString)
    }

    fun load(path: Path): SpaceManifest {
        val jsonString = Files.readString(path)
        val dto = json.decodeFromString<SpaceManifestDto>(jsonString)

        return SpaceManifest(
            version = dto.version,
            spaceId = UUID.fromString(dto.spaceId),
            created = Instant.parse(dto.created),
            indices = dto.indices.map { idx ->
                IndexMetadata(
                    id = idx.id,
                    file = idx.file,
                    pattern = idx.pattern,
                    created = Instant.parse(idx.created),
                    bindingsCount = idx.bindingsCount
                )
            }
        )
    }
}