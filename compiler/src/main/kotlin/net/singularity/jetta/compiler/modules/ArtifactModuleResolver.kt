package net.singularity.jetta.compiler.modules

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.resolve.ModuleInterface
import net.singularity.jetta.compiler.frontend.rewrite.PrecompiledModule
import net.singularity.jetta.compiler.frontend.rewrite.PrecompiledModuleResolver
import net.singularity.jetta.runtime.space.SpaceDirectorySerializer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds an already-compiled module in a list of artifact DIRECTORIES: `<name>.jctx` for its
 * interface, `<name>.jtsf` for its space and `<name>.manifest.json`, which carries the binary uuid
 * the space file is validated against. Directories are searched in order, first hit wins.
 *
 * A module counts as available only when all three are there and the interface parses to at least
 * one entry. Anything less is treated as "not precompiled", so the import falls back to the source
 * path rather than half-linking against an incomplete artifact set — a truncated artifact should
 * cost a slower compile, not a broken program.
 *
 * The `.class` is deliberately NOT required here: the compiler never reads it, and whether it is
 * next to the artifacts or inside a jar on the runtime classpath is the runtime's problem.
 */
class ArtifactModuleResolver(private val directories: List<Path>) : PrecompiledModuleResolver {

    override fun find(name: String): PrecompiledModule? {
        for (dir in directories) {
            val interfaceFile = dir.resolve("$name.jctx")
            val required = listOf(interfaceFile, dir.resolve("$name.jtsf"), dir.resolve("$name.manifest.json"))
            if (required.any { !Files.isRegularFile(it) }) continue
            val entries = runCatching { ModuleInterface.parseAll(Files.readString(interfaceFile)) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            val atoms = runCatching {
                SpaceDirectorySerializer.load(dir, name).getAtoms().map {
                    it as? Expression ?: Expression(listOf(it))
                }
            }.getOrNull() ?: continue
            return PrecompiledModule(name = name, entries = entries, atoms = atoms)
        }
        return null
    }
}
