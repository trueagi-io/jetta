package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ParserFacade
import net.singularity.jetta.compiler.frontend.Source
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Predefined
import net.singularity.jetta.compiler.frontend.ir.Run
import net.singularity.jetta.compiler.frontend.ir.SourcePosition
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.rewrite.messages.CyclicImportMessage
import net.singularity.jetta.compiler.frontend.rewrite.messages.ImportAsNotImplementedMessage
import net.singularity.jetta.compiler.frontend.rewrite.messages.InvalidModuleNameMessage
import net.singularity.jetta.compiler.frontend.rewrite.messages.MissingModuleMessage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Compile-time pass that turns `(import! &self <name>)` into actual module loading.
 *
 * For every top-level `Run` whose expression matches `(import! <space-ref> <module>)`,
 * the pass:
 *   - Resolves `<module>` to a sibling `.metta` under the importer's directory.
 *   - Recursively parses and processes that module the same way.
 *   - Stores the resulting [ParsedSource] in [cache] so the surrounding [net.singularity.jetta.compiler.Compiler]
 *     can add it to its compilation queue.
 *   - Removes the `import!` Run from the importer's source — it is purely a compile-time
 *     directive with no runtime equivalent.
 *
 * Anything other than `&self` as the space reference is rejected with an
 * [ImportAsNotImplementedMessage]; cycles surface a [CyclicImportMessage]; unreadable
 * targets surface a [MissingModuleMessage]; names that don't match `[A-Za-z0-9_-]+`
 * surface an [InvalidModuleNameMessage]. Errors do not abort the pass — multiple
 * problems in one source are reported together.
 */
class ImportResolutionPass(
    private val parser: ParserFacade,
    private val cache: ModuleCompilationCache,
    private val messageCollector: MessageCollector,
) {
    private val moduleNamePattern = Regex("^[A-Za-z0-9_-]+$")

    /** Resolve all imports reachable from [source]. Returns a transformed [ParsedSource]
     * with each `import!` Run replaced by the `!`-Runs the imported module would execute
     * at load time, in source order. On cache hit (the module was already loaded earlier
     * in the same compile) nothing is spliced, matching MeTTa's load-once semantics for
     * import-time effects. Errors and cycles also splice nothing. Declarations from the
     * imported module are not duplicated here — they live in [cache].`resolved` and are
     * picked up by the surrounding compiler driver. */
    fun resolve(source: ParsedSource, sourcePath: Path): ParsedSource {
        val survivors = mutableListOf<Atom>()
        for (atom in source.code) {
            val request = matchImport(atom)
            if (request == null) {
                survivors.add(atom)
                continue
            }
            val splicedRuns = handleImport(request, sourcePath)
            survivors.addAll(splicedRuns)
        }
        return ParsedSource(source.filename, survivors)
    }

    /** Detect a top-level `(import! <space> <module>)` form. Returns null otherwise. */
    private fun matchImport(atom: Atom): ImportRequest? {
        if (atom !is Run) return null
        val expr = atom.expression
        val atoms = expr.atoms
        if (atoms.size < 3) return null
        val head = atoms[0] as? Symbol ?: return null
        if (head.name != "import!") return null
        return ImportRequest(
            spaceRef = atoms[1],
            moduleAtom = atoms[2],
            position = atom.position,
        )
    }

    /**
     * Process one `(import! &self <name>)` request. Returns the list of `!`-Runs that
     * should be spliced into the importer at the position of this directive — non-empty
     * on the first successful load of the target, empty on cache hit (idempotent: load-
     * time effects fire once), empty on error. The error itself is reported via
     * [messageCollector]; the caller continues so multiple problems surface together.
     */
    private fun handleImport(req: ImportRequest, importerPath: Path): List<Run> {
        // 1. Validate space-ref. Only &self is supported in v1.
        val spaceSym = req.spaceRef as? Symbol
        if (spaceSym == null || spaceSym.name != Predefined.SELF) {
            val targetName = (req.spaceRef as? Symbol)?.name ?: req.spaceRef.toString()
            messageCollector.add(ImportAsNotImplementedMessage(targetName, req.position))
            return emptyList()
        }

        // 2. Validate module name.
        val moduleSym = req.moduleAtom as? Symbol
        if (moduleSym == null) {
            messageCollector.add(InvalidModuleNameMessage(req.moduleAtom.toString(), req.position))
            return emptyList()
        }
        val moduleName = moduleSym.name
        if (!moduleNamePattern.matches(moduleName)) {
            messageCollector.add(InvalidModuleNameMessage(moduleName, req.position))
            return emptyList()
        }

        // 3. Resolve to a canonical sibling path.
        val parent = importerPath.toAbsolutePath().normalize().parent ?: Paths.get(".").toAbsolutePath()
        val targetPath = parent.resolve("$moduleName.metta").toAbsolutePath().normalize()
        val canonicalImporter = importerPath.toAbsolutePath().normalize()

        // 4. Cycle?
        if (targetPath in cache.resolving) {
            messageCollector.add(
                CyclicImportMessage(importerPath.toString(), targetPath.toString(), req.position)
            )
            return emptyList()
        }

        // 5. Already resolved? Idempotent: load-time effects fired on first import,
        //    subsequent imports are no-ops for `!`-Runs. Edge is still recorded so the
        //    storage strategy sees this importer's diamond view.
        if (targetPath in cache.resolved) {
            cache.imports.getOrPut(canonicalImporter) { mutableSetOf() }.add(targetPath)
            return emptyList()
        }

        // 6. Read + parse + recurse.
        if (!Files.isRegularFile(targetPath)) {
            messageCollector.add(MissingModuleMessage(moduleName, targetPath.toString(), req.position))
            return emptyList()
        }
        val text = try {
            Files.readString(targetPath)
        } catch (_: Throwable) {
            messageCollector.add(MissingModuleMessage(moduleName, targetPath.toString(), req.position))
            return emptyList()
        }

        cache.resolving.add(targetPath)
        try {
            val parsed = parser.parse(Source(targetPath.toString(), text), messageCollector)
            val resolved = resolve(parsed, targetPath)
            cache.resolved[targetPath] = resolved
            cache.imports.getOrPut(canonicalImporter) { mutableSetOf() }.add(targetPath)
            // First load: splice the imported module's `!`-Runs (which already include
            // any of its own transitively-imported Runs at the right positions, because
            // the recursive resolve above performed the same splicing inside it).
            //
            // Clone each Run so the importer and the imported module's own __main hold
            // distinct instances. Without the clone they share `id` and mutable IR
            // fields populated by later rewriters/resolvers, which causes the second
            // pass over the shared instance to skip work it should redo for its new
            // owner — effectively dropping the spliced Run from main's output.
            return resolved.code.filterIsInstance<Run>().map { Run(it.expression, it.position) }
        } finally {
            cache.resolving.remove(targetPath)
        }
    }

    private data class ImportRequest(
        val spaceRef: Atom,
        val moduleAtom: Atom,
        val position: SourcePosition?,
    )
}
