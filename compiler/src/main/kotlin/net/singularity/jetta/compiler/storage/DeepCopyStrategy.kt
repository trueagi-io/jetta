package net.singularity.jetta.compiler.storage

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.resolve.getJvmClassName
import net.singularity.jetta.compiler.frontend.rewrite.ModuleCompilationCache
import net.singularity.jetta.runtime.space.ManifestExtension
import net.singularity.jetta.runtime.space.ModuleLoad
import net.singularity.jetta.runtime.space.Space
import net.singularity.jetta.runtime.space.SpaceImpl
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Default Track 2E strategy. For each compiled source `S`:
 *
 *   `effective(S) = own(S) ∪ ⋃ effective(I)` for each `&self`-import `I` of `S`
 *
 * with diamond dedup at the module level (an `Expression` from a shared module
 * contributes once to any importer's effective space, regardless of how many import
 * paths reach it). Each effective space is a fresh [SpaceImpl]; atom instances may be
 * shared (the IR is immutable), but the containing space is per-module so future
 * `add-atom!` mutations stay isolated.
 */
object DeepCopyStrategy : StorageStrategy {
    override val kind: String = "deep-copy"

    override fun computeSpaces(
        userSources: List<ParsedSource>,
        cache: ModuleCompilationCache,
        atomsBySource: Map<ParsedSource, List<Expression>>,
        importsBySource: Map<ParsedSource, Set<Path>>,
    ): Map<ParsedSource, Space> {
        // We need both: lookup ParsedSource by canonical path (for traversal) and
        // a complete list of all sources to compute spaces for.
        val byPath: Map<Path, ParsedSource> = buildMap {
            putAll(cache.resolved)
            userSources.forEach { put(canonicalPath(it), it) }
        }

        val spaces = mutableMapOf<ParsedSource, Space>()
        val cachedAtoms = mutableMapOf<ParsedSource, List<Expression>>()

        fun effective(source: ParsedSource): List<Expression> = cachedAtoms.getOrPut(source) {
            // BFS over the &self-import DAG starting at `source`. Visited at the
            // module level so a diamond contributes once.
            val seen = mutableSetOf<ParsedSource>()
            val ordered = mutableListOf<Expression>()
            val queue = ArrayDeque<ParsedSource>()
            queue.add(source)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!seen.add(current)) continue
                atomsBySource[current]?.let(ordered::addAll)
                importsBySource[current].orEmpty().forEach { importedPath ->
                    byPath[importedPath]?.let { queue.add(it) }
                }
            }
            ordered
        }

        // Compute for every source we know about — both user entries and imported
        // modules. Imported-only modules need their own .jtsf because their compiled
        // bytecode runs at INVOKESTATIC time and does match("their-name", …) lookups.
        val allSources = (userSources + cache.resolved.values).toSet()
        for (source in allSources) {
            val space = SpaceImpl()
            effective(source).forEach(space::add)
            spaces[source] = space
        }
        return spaces
    }

    override fun manifestExtensionFor(
        source: ParsedSource,
        cache: ModuleCompilationCache,
        importsBySource: Map<ParsedSource, Set<Path>>,
    ): ManifestExtension {
        val byPath: Map<Path, ParsedSource> = cache.resolved
        val seen = mutableSetOf<Path>()
        val ordered = mutableListOf<ParsedSource>()
        val queue = ArrayDeque<ParsedSource>()
        importsBySource[source].orEmpty().forEach { queue.add(byPath[it] ?: return@forEach) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val currentPath = canonicalPath(current)
            if (!seen.add(currentPath)) continue
            ordered.add(current)
            importsBySource[current].orEmpty().forEach { importedPath ->
                byPath[importedPath]?.let { queue.add(it) }
            }
        }
        val moduleLoads = ordered.map { module ->
            val name = module.getJvmClassName().substringAfterLast('/')
            ModuleLoad(spaceId = name, jtsf = "$name.jtsf")
        }
        return ManifestExtension.DeepCopy(loadModules = moduleLoads)
    }

    private fun canonicalPath(source: ParsedSource): Path =
        Paths.get(source.filename).toAbsolutePath().normalize()
}
