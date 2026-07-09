package net.singularity.jetta.runtime.space

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry mapping [SpaceId] to live [Space] instances.
 *
 * Replaces the previous single static `Space` field in `JettaProgram`: identifying a
 * space by its [SpaceId] rather than by its position in code is the foundation that
 * later changes (per-module space binding, named sub-spaces) build on.
 *
 * For the present pipeline only one space is registered at a time — the entry program's —
 * so this object behaves observably the same as the old singleton. The shape exists so
 * follow-up work can register additional modules without touching call sites.
 *
 * Thread safety: backed by [ConcurrentHashMap] so concurrent test runners can register
 * and look up without external synchronization. Mutation of the contained [Space] itself
 * remains the caller's responsibility (the underlying [SpaceImpl] is not thread-safe).
 */
object SpaceRegistry {
    private val spaces = ConcurrentHashMap<SpaceId, Space>()

    /** Register [space] under [id], replacing any existing entry for that id. */
    fun register(id: SpaceId, space: Space) {
        spaces[id] = space
    }

    /** Return the space registered under [id], or `null` if no registration exists. */
    fun get(id: SpaceId): Space? = spaces[id]

    /**
     * Return the existing entry for [id] or, when missing, create an empty [SpaceImpl],
     * register it under [id], and return it. Lets callers treat unfamiliar ids as
     * lazily-initialised empty spaces — handy for programs that haven't been through
     * `init` yet (early-test scenarios, the REPL).
     */
    fun getOrCreate(id: SpaceId): Space =
        spaces.getOrPut(id) { SpaceImpl() }

    /** Drop every registration. Used by `JettaProgram.init` between programs and by tests. */
    fun reset() {
        spaces.clear()
    }
}
