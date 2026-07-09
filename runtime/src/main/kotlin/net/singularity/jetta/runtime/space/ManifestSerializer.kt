package net.singularity.jetta.runtime.space

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Reads and writes the v2 manifest format. v1 manifests are rejected with
 * [IncompatibleManifestException]; users recompile.
 */
object ManifestSerializer {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class IndexMetadataDto(
        val id: String,
        val file: String,
        val pattern: String,
        val created: String,
        val bindingsCount: Int,
    )

    @Serializable
    private data class ModuleLoadDto(
        val spaceId: String,
        val jtsf: String,
    )

    @Serializable
    private data class ManifestV2Dto(
        val version: Int,
        val kind: String,
        val spaceId: String,
        val binaryUuid: String,
        val created: String,
        val indices: List<IndexMetadataDto>,
        val loadModules: List<ModuleLoadDto> = emptyList(),
        val aliases: List<String> = emptyList(),
        val atomCount: Int = 0,
        val contentHash: String = "",
    )

    fun save(manifest: ManifestV2, path: Path) {
        val (loadModules, aliases) = when (val ext = manifest.extension) {
            is ManifestExtension.DeepCopy ->
                ext.loadModules.map { ModuleLoadDto(it.spaceId, it.jtsf) } to emptyList()
            is ManifestExtension.Alias ->
                emptyList<ModuleLoadDto>() to ext.aliases
        }
        val dto = ManifestV2Dto(
            version = manifest.version,
            kind = manifest.kind,
            spaceId = manifest.spaceId,
            binaryUuid = manifest.binaryUuid.toString(),
            created = manifest.created.toString(),
            indices = manifest.indices.map { idx ->
                IndexMetadataDto(
                    id = idx.id,
                    file = idx.file,
                    pattern = idx.pattern,
                    created = idx.created.toString(),
                    bindingsCount = idx.bindingsCount,
                )
            },
            loadModules = loadModules,
            aliases = aliases,
            atomCount = manifest.atomCount,
            contentHash = manifest.contentHash,
        )

        Files.writeString(path, json.encodeToString(dto))
    }

    fun load(path: Path): ManifestV2 {
        val jsonString = Files.readString(path)
        // Probe version first so a v1 file (where `version` is the string "1.0" and
        // `kind` is absent) errors with a clear message rather than a deserialization
        // crash from the Int-typed v2 schema.
        val rootEl = json.parseToJsonElement(jsonString)
        if (rootEl !is JsonObject) {
            throw IncompatibleManifestException("manifest at $path is not a JSON object")
        }
        val versionEl = rootEl["version"]
        val versionInt: Int? = when (versionEl) {
            is JsonPrimitive -> versionEl.intOrNull
            else -> null
        }
        if (versionInt != 2) {
            throw IncompatibleManifestException(
                "manifest at $path has version=${versionEl?.jsonPrimitive?.content ?: "<missing>"}; " +
                        "Track 2E requires version=2 — recompile to refresh the artefact."
            )
        }

        val dto = json.decodeFromString<ManifestV2Dto>(jsonString)

        val extension: ManifestExtension = when (dto.kind) {
            "deep-copy" -> ManifestExtension.DeepCopy(
                loadModules = dto.loadModules.map { ModuleLoad(it.spaceId, it.jtsf) }
            )
            "alias" -> ManifestExtension.Alias(aliases = dto.aliases)
            else -> throw IncompatibleManifestException(
                "manifest at $path declares unknown kind=${dto.kind}"
            )
        }

        return ManifestV2(
            version = dto.version,
            kind = dto.kind,
            spaceId = dto.spaceId,
            binaryUuid = UUID.fromString(dto.binaryUuid),
            created = Instant.parse(dto.created),
            indices = dto.indices.map { idx ->
                IndexMetadata(
                    id = idx.id,
                    file = idx.file,
                    pattern = idx.pattern,
                    created = Instant.parse(idx.created),
                    bindingsCount = idx.bindingsCount,
                )
            },
            extension = extension,
            atomCount = dto.atomCount,
            contentHash = dto.contentHash,
        )
    }
}
