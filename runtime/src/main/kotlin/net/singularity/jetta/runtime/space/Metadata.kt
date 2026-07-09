package net.singularity.jetta.runtime.space

import java.time.Instant
import java.util.UUID

/**
 * Metadata about a single index.
 */
data class IndexMetadata(
    val id: String,
    val file: String,
    val pattern: String,
    val created: Instant,
    val bindingsCount: Int
)

/**
 * Pointer the runtime uses to bring a transitively-imported module's space into the
 * registry alongside the entry program's space (Track 2E DeepCopy).
 */
data class ModuleLoad(
    val spaceId: String,
    val jtsf: String,
)

/**
 * Strategy-specific manifest payload. Each [StorageStrategy] supplies one of these.
 * DeepCopy carries the list of transitively-imported modules to load at init time.
 * Alias (Track 2F) will carry token aliases for Import-As.
 */
sealed interface ManifestExtension {
    data class DeepCopy(val loadModules: List<ModuleLoad>) : ManifestExtension
    data class Alias(val aliases: List<String>) : ManifestExtension
}

/**
 * Manifest format v2 — written by `Compiler` after Track 2E and read by `JettaProgram.init`.
 *
 * - [spaceId] is the *module name* the registry keys this space under (i.e. the value
 *   that ends up wrapped in `SpaceId.FromModule`). It must be portable across machines.
 * - [binaryUuid] is an internal random identity used to verify that the `.jtsf` binary
 *   file and the manifest belong to the same artefact pair.
 * - [kind] mirrors `StorageStrategy.kind` so `JettaProgram.init` can dispatch to the
 *   right loader.
 * - [extension] carries strategy-specific data (e.g. DeepCopy's loadModules list).
 * - [atomCount] / [contentHash] are the space fingerprint (see `SpaceDigest`), also baked
 *   into the compiled program; `JettaProgram.init` compares the two to detect a
 *   wrong/stale/absent artifact pair. Default 0/"" keeps older manifests loadable.
 */
data class ManifestV2(
    val version: Int = 2,
    val kind: String,
    val spaceId: String,
    val binaryUuid: UUID,
    val created: Instant,
    val indices: List<IndexMetadata>,
    val extension: ManifestExtension,
    val atomCount: Int = 0,
    val contentHash: String = "",
)

/**
 * Thrown when the on-disk manifest is from a previous artefact format. Track 2E breaks
 * compatibility with v1; users must recompile.
 */
class IncompatibleManifestException(message: String) : RuntimeException(message)
