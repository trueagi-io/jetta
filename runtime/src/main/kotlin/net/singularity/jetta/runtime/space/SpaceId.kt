package net.singularity.jetta.runtime.space

import java.util.UUID

/**
 * Stable identity of a `Space` instance, independent of any filesystem path.
 *
 * Identities are surfaced into bytecode (as their string name, see [SpaceId.FromModule.name])
 * and on-disk artefacts, so the value must be portable across machines: a module compiled
 * on one host and run on another must resolve to the same `SpaceId`. Filesystem paths are
 * intentionally not part of this type — they are a compile-time concern only and do not
 * enter IR or runtime.
 */
sealed class SpaceId {
    /**
     * A space derived from a source module.
     *
     * `name` is a stable, machine-portable string — typically the module file's basename
     * without extension. Two modules with the same name resolve to the same id.
     */
    data class FromModule(val name: String) : SpaceId()

    /**
     * A space created at runtime that has no source-module backing — e.g. via a future
     * `new-space` built-in. Reserved for v2; not produced by the v1 pipeline.
     */
    data class Synthetic(val uuid: UUID) : SpaceId()

    /**
     * Sentinel id for unit tests, the REPL, or any transient context where the space
     * isn't owned by a named module. Equality semantics are `data object`-style: there
     * is one Anonymous identity, used wherever no other id makes sense.
     */
    data object Anonymous : SpaceId()
}
