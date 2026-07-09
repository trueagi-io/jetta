package net.singularity.jetta.compiler.storage

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.rewrite.ModuleCompilationCache
import net.singularity.jetta.runtime.space.ManifestExtension
import net.singularity.jetta.runtime.space.Space
import java.nio.file.Path

/**
 * Compile-time pluggable storage shape.
 *
 * The shipping strategy is [DeepCopyStrategy], which produces per-module `.jtsf`
 * artefacts whose effective space is the importer's own atoms plus a deep-clone view
 * of every transitively-`&self`-imported module's own atoms. The shape exists so a
 * future alias / copy-on-write / etc. strategy can plug in without re-touching the
 * compiler core: see [AliasStrategy] for the placeholder.
 *
 * The runtime-side counterpart of this dispatch lives in `JettaProgram.init`, which
 * reads `manifest.kind` and acts on the strategy-specific `ManifestExtension`.
 */
sealed interface StorageStrategy {
    /** Identifier persisted in the manifest so `init` knows which loader to dispatch. */
    val kind: String

    /**
     * Produce per-source effective spaces ready for serialization. Receives:
     *  - [userSources]: the entry programs the user passed to the compiler
     *  - [cache]: the ImportResolutionPass cache (knows transitive imports)
     *  - [atomsBySource]: each source's own top-level facts (collected by the
     *    `FunctionRewriter`'s ownAtomsCollector during the rewriter pass)
     *  - [importsBySource]: for each source, the canonical paths it directly
     *    `&self`-imports
     *
     * Returns a `Map<ParsedSource, Space>` giving each compiled source its own
     * ready-to-serialize [Space]. Both user sources and imported modules from the
     * cache are populated.
     */
    fun computeSpaces(
        userSources: List<ParsedSource>,
        cache: ModuleCompilationCache,
        atomsBySource: Map<ParsedSource, List<Expression>>,
        importsBySource: Map<ParsedSource, Set<Path>>,
    ): Map<ParsedSource, Space>

    /**
     * Build the manifest extension for [source]. For DeepCopy this lists every
     * transitively-imported module so the runtime can pre-load their `.jtsf` blobs
     * into the registry. For Alias (future) this lists the alias names.
     */
    fun manifestExtensionFor(
        source: ParsedSource,
        cache: ModuleCompilationCache,
        importsBySource: Map<ParsedSource, Set<Path>>,
    ): ManifestExtension
}
