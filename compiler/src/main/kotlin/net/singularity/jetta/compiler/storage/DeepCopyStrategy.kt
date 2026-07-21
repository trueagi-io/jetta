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
 *   `effective(S) = own(S)`
 *
 * Each module's serialized space holds ONLY its own top-level atoms. Imports are no
 * longer merged statically: `import!` is a runtime, order-sensitive operation (see
 * `JettaProgram.import!`) that copies a module's atoms into the target space at the point
 * of execution — the only model compatible with hyperon's order-sensitive `match &self`
 * (a `match` before an `import!` must not see the imported atoms; after it must). The
 * import graph is still recorded and surfaced in [manifestExtensionFor] so every reachable
 * module's `.jtsf` is loaded at `init` under its own id, ready for the runtime copy. Each
 * space is a fresh [SpaceImpl]; atom instances may be shared (the IR is immutable), but the
 * container is per-module so runtime mutations stay isolated.
 */
object DeepCopyStrategy : StorageStrategy {
    override val kind: String = "deep-copy"

    override fun computeSpaces(
        userSources: List<ParsedSource>,
        cache: ModuleCompilationCache,
        atomsBySource: Map<ParsedSource, List<Expression>>,
        importsBySource: Map<ParsedSource, Set<Path>>,
    ): Map<ParsedSource, Space> {
        val spaces = mutableMapOf<ParsedSource, Space>()

        // Compute for every source we know about — both user entries and imported
        // modules. Each holds only its OWN atoms; the runtime `import!` copies them into
        // whichever space its call targets, in program order. Imported-only modules still
        // need their own .jtsf so init can register them for that copy.
        val allSources = (userSources + cache.resolved.values).toSet()
        for (source in allSources) {
            val space = SpaceImpl()
            atomsBySource[source].orEmpty().forEach(space::add)
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
