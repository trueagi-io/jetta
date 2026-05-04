package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.ParsedSource
import java.nio.file.Path

/**
 * Per-`Compiler.compile()` cache for transitively-imported modules.
 *
 * Keyed by the canonical absolute path of each `.metta` file so that the same module
 * referenced from multiple importers (a diamond) is parsed and processed exactly once.
 *
 * [resolving] is the recursion stack used for cycle detection: a path is added before
 * recursive resolution and removed after. Any attempt to enter a path that is already
 * on the stack is a cycle.
 *
 * [imports] records the import-graph edges: for each importer's canonical path, the set
 * of canonical paths it directly `&self`-imports. Populated by [ImportResolutionPass]
 * each time a `(import! &self <name>)` resolves successfully (including diamond cache
 * hits — the edge is recorded regardless of whether it's a fresh load or a re-entry).
 * Track 2E uses this graph to compute each module's effective space.
 */
class ModuleCompilationCache {
    val resolved: MutableMap<Path, ParsedSource> = mutableMapOf()
    val resolving: MutableSet<Path> = mutableSetOf()
    val imports: MutableMap<Path, MutableSet<Path>> = mutableMapOf()
}
