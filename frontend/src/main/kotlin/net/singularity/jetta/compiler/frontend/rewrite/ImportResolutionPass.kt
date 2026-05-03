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
     * with the `import!` Runs removed. Imported modules end up in [cache].`resolved`. */
    fun resolve(source: ParsedSource, sourcePath: Path): ParsedSource {
        val survivors = mutableListOf<Atom>()
        for (atom in source.code) {
            val request = matchImport(atom)
            if (request == null) {
                survivors.add(atom)
                continue
            }
            handleImport(request, sourcePath)
            // Whether handling succeeded or produced an error, the Run itself is dropped:
            // the directive has been "consumed" at compile time.
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

    private fun handleImport(req: ImportRequest, importerPath: Path) {
        // 1. Validate space-ref. Only &self is supported in v1.
        val spaceSym = req.spaceRef as? Symbol
        if (spaceSym == null || spaceSym.name != Predefined.SELF) {
            val targetName = (req.spaceRef as? Symbol)?.name ?: req.spaceRef.toString()
            messageCollector.add(ImportAsNotImplementedMessage(targetName, req.position))
            return
        }

        // 2. Validate module name.
        val moduleSym = req.moduleAtom as? Symbol
        if (moduleSym == null) {
            messageCollector.add(InvalidModuleNameMessage(req.moduleAtom.toString(), req.position))
            return
        }
        val moduleName = moduleSym.name
        if (!moduleNamePattern.matches(moduleName)) {
            messageCollector.add(InvalidModuleNameMessage(moduleName, req.position))
            return
        }

        // 3. Resolve to a canonical sibling path.
        val parent = importerPath.toAbsolutePath().normalize().parent ?: Paths.get(".").toAbsolutePath()
        val targetPath = parent.resolve("$moduleName.metta").toAbsolutePath().normalize()

        // 4. Cycle?
        if (targetPath in cache.resolving) {
            messageCollector.add(
                CyclicImportMessage(importerPath.toString(), targetPath.toString(), req.position)
            )
            return
        }

        // 5. Already resolved?
        if (targetPath in cache.resolved) return

        // 6. Read + parse + recurse.
        if (!Files.isRegularFile(targetPath)) {
            messageCollector.add(MissingModuleMessage(moduleName, targetPath.toString(), req.position))
            return
        }
        val text = try {
            Files.readString(targetPath)
        } catch (_: Throwable) {
            messageCollector.add(MissingModuleMessage(moduleName, targetPath.toString(), req.position))
            return
        }

        cache.resolving.add(targetPath)
        try {
            val parsed = parser.parse(Source(targetPath.toString(), text), messageCollector)
            val resolved = resolve(parsed, targetPath)
            cache.resolved[targetPath] = resolved
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
