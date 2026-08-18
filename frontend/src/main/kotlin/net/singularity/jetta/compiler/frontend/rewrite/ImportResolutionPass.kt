package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ParserFacade
import net.singularity.jetta.compiler.frontend.Source
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Run
import net.singularity.jetta.compiler.frontend.ir.SourcePosition
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.rewrite.messages.CyclicImportMessage
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

    private companion object {
        const val SELF = "&self"
    }

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
            // Runtime-ordered import (hyperon semantics): compile the target module and
            // record the import edge so its `.jtsf` is listed in the manifest and loaded
            // at `init`, then KEEP the `(import! …)` Run so it reaches codegen and executes
            // in program order (JettaProgram.import! copies the module's atoms into the
            // target space at that point). This replaces the old compile-time static merge,
            // which could not honour the order-sensitive `match &self` asserts in c2_spaces.
            // The imported module's own `!`-Runs are still spliced AFTER the import directive,
            // so a module's load-time effects run once, in order, after its atoms are in place.
            val splicedRuns = ensureModuleCompiled(request, sourcePath)
            survivors.add(atom)
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
     * Ensure the module named by one `(import! <space> <name>)` request is compiled and its
     * import edge recorded, and return the module's own `!`-Runs to splice after the import
     * directive (empty on a diamond cache hit or error — load-time effects fire once). The
     * edge drives two things: the module is listed in the importer's manifest and loaded at
     * `init` under `SpaceId.FromModule(name)`, ready for the runtime `import!` to copy its
     * atoms into the target space. The `<space>` reference itself is NOT inspected here — it
     * is carried by the surviving `(import! …)` Run and resolved at runtime — so `&self`,
     * `&kb`, `&m`, … are all handled uniformly. Errors are reported via [messageCollector];
     * the caller continues so multiple problems surface together.
     */
    private fun ensureModuleCompiled(req: ImportRequest, importerPath: Path): List<Run> {
        // 1. Validate module name.
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

        // 2. Resolve to a canonical sibling path.
        val parent = importerPath.toAbsolutePath().normalize().parent ?: Paths.get(".").toAbsolutePath()
        val targetPath = parent.resolve("$moduleName.metta").toAbsolutePath().normalize()
        val canonicalImporter = importerPath.toAbsolutePath().normalize()

        // 3. Cycle?
        if (targetPath in cache.resolving) {
            messageCollector.add(
                CyclicImportMessage(importerPath.toString(), targetPath.toString(), req.position)
            )
            return emptyList()
        }

        // 4. Already resolved (diamond)? Record the edge and stop — the module is compiled
        //    exactly once and its load-time runs fired on first import.
        if (targetPath in cache.resolved) {
            cache.imports.getOrPut(canonicalImporter) { mutableSetOf() }.add(targetPath)
            return emptyList()
        }

        // 5. Read + parse + recurse.
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
            // Clone each Run so the importer and the imported module's own __main hold
            // distinct instances (they otherwise share `id` and mutable IR fields that
            // later passes populate per-owner), and rebind `&self` to the MODULE's own space.
            // A module's load-time effects belong to the module: moduleA's
            // `!(import! &self f1_moduleC)`, spliced verbatim, imported moduleC into the
            // IMPORTER instead — so `g` was reachable from &self at line 1 of f1_imports even
            // though moduleA reaches &self only at :61. Runs spliced from a deeper module were
            // already rebound during that module's own resolve, so only the `&self`s this
            // module wrote itself are left to rewrite here.
            return resolved.code.filterIsInstance<Run>()
                .map { Run(rebindSelf(it.expression, moduleName) as Expression, it.position) }
        } finally {
            cache.resolving.remove(targetPath)
        }
    }

    /** Replace every `&self` reference in [atom] with [moduleName], the module's own space. */
    private fun rebindSelf(atom: Atom, moduleName: String): Atom = when {
        atom is Symbol && atom.name == SELF -> Symbol(moduleName, atom.position)
        atom is Expression -> Expression(
            atoms = atom.atoms.map { rebindSelf(it, moduleName) },
            position = atom.position,
        )
        else -> atom
    }

    private data class ImportRequest(
        val spaceRef: Atom,
        val moduleAtom: Atom,
        val position: SourcePosition?,
    )
}
