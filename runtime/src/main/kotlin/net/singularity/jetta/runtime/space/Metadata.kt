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
 * Manifest file containing metadata about the space and its indices.
 */
data class SpaceManifest(
    val version: String = "1.0",
    val spaceId: UUID,
    val created: Instant,
    val indices: List<IndexMetadata>
)