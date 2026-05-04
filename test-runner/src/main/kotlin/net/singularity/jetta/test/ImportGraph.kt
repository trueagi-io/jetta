package net.singularity.jetta.test

import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.Source
import net.singularity.jetta.compiler.frontend.ir.Run
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.parser.antlr.AntlrParserFacadeImpl
import java.io.File

/**
 * Discovers which `.metta` files in a suite act as modules (that is, are referenced
 * by `(import! <space> <name>)` from at least one other file). Module-only files are
 * meant to be loaded by an importer, so the test runner skips them as standalone
 * entries.
 *
 * Discovery parses each file with the actual JeTTa parser, so an `import!` token
 * inside a comment, a string literal, or any other non-Run context does not count.
 * Files that fail to parse are skipped silently — their broken state is the
 * surrounding compile run's problem, not the runner's.
 */
object ImportGraph {

    /**
     * Return the set of module *names* (file basename without `.metta`) imported by
     * any file in [files]. The result may contain names that don't match an actual
     * file in the suite — the caller intersects with the real file set if needed.
     */
    fun importedNames(files: Collection<File>): Set<String> {
        val parser = AntlrParserFacadeImpl()
        val out = mutableSetOf<String>()
        for (file in files) {
            val text = try {
                file.readText()
            } catch (_: Throwable) {
                continue
            }
            val collector = MessageCollector()
            val parsed = try {
                parser.parse(Source(file.absolutePath, text), collector)
            } catch (_: Throwable) {
                continue
            }
            for (atom in parsed.code) {
                if (atom !is Run) continue
                val atoms = atom.expression.atoms
                if (atoms.size < 3) continue
                if ((atoms[0] as? Symbol)?.name != "import!") continue
                // atoms[1] is the space-ref (&self / &kb / …); we don't care which.
                val moduleName = (atoms[2] as? Symbol)?.name ?: continue
                out.add(moduleName)
            }
        }
        return out
    }
}
