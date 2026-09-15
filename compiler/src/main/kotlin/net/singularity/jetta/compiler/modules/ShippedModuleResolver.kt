package net.singularity.jetta.compiler.modules

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.resolve.ModuleInterface
import net.singularity.jetta.compiler.frontend.rewrite.PrecompiledModule
import net.singularity.jetta.compiler.frontend.rewrite.PrecompiledModuleResolver
import net.singularity.jetta.runtime.space.ArtifactSource
import net.singularity.jetta.runtime.space.SpaceDirectorySerializer

/**
 * Finds a module SHIPPED inside the compiler's own jar — the vendored standard library, built once
 * per release by the `compileStdlib` task and packed under [ArtifactSource.SHIPPED_MODULE_PREFIX].
 *
 * Marked as not overriding source: a program that carries its own `stdlib.metta` compiles that
 * one. The runtime makes the same choice from the other side, looking in the program's artifact
 * directory before the jar, so both ends link the same module.
 */
class ShippedModuleResolver(
    private val source: ArtifactSource = ArtifactSource.shipped(),
) : PrecompiledModuleResolver {

    override fun find(name: String): PrecompiledModule? {
        val interfaceText = source.readBytes("$name.jctx")?.toString(Charsets.UTF_8) ?: return null
        val entries = ModuleInterface.parseAll(interfaceText).takeIf { it.isNotEmpty() } ?: return null
        val space = runCatching { SpaceDirectorySerializer.loadOrNull(source, name) }.getOrNull() ?: return null
        return PrecompiledModule(
            name = name,
            entries = entries,
            atoms = space.getAtoms().map { it as? Expression ?: Expression(listOf(it)) },
            overridesSource = false,
        )
    }
}

/**
 * Asks each resolver in turn — `--module-path` directories first, then the shipped modules — and
 * answers with the first hit. The order here is only about WHERE a module is found; whether that
 * beats the module's source is carried by [PrecompiledModule.overridesSource].
 */
class CompositeModuleResolver(
    private val resolvers: List<PrecompiledModuleResolver>,
) : PrecompiledModuleResolver {
    override fun find(name: String): PrecompiledModule? =
        resolvers.firstNotNullOfOrNull { it.find(name) }
}
