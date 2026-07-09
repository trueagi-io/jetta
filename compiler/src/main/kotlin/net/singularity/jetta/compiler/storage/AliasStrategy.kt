package net.singularity.jetta.compiler.storage

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.rewrite.ModuleCompilationCache
import net.singularity.jetta.runtime.space.ManifestExtension
import net.singularity.jetta.runtime.space.Space
import java.nio.file.Path

/**
 * Stub for the alias / Import-As storage model. Reserved for Track 2E.5 — meant for
 * side-by-side comparison against [DeepCopyStrategy] when discussing canonical
 * Import semantics with the MeTTa creators. Not selectable from the CLI in v1.
 */
object AliasStrategy : StorageStrategy {
    override val kind: String = "alias"

    override fun computeSpaces(
        userSources: List<ParsedSource>,
        cache: ModuleCompilationCache,
        atomsBySource: Map<ParsedSource, List<Expression>>,
        importsBySource: Map<ParsedSource, Set<Path>>,
    ): Map<ParsedSource, Space> = TODO("Track 2E.5 — alias strategy not yet implemented")

    override fun manifestExtensionFor(
        source: ParsedSource,
        cache: ModuleCompilationCache,
        importsBySource: Map<ParsedSource, Set<Path>>,
    ): ManifestExtension = TODO("Track 2E.5 — alias strategy not yet implemented")
}
