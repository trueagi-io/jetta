package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Run
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.rewrite.ImportResolutionPass
import net.singularity.jetta.compiler.frontend.rewrite.ModuleCompilationCache
import net.singularity.jetta.compiler.frontend.rewrite.messages.CyclicImportMessage
import net.singularity.jetta.compiler.frontend.rewrite.messages.InvalidModuleNameMessage
import net.singularity.jetta.compiler.frontend.rewrite.messages.MissingModuleMessage
import net.singularity.jetta.compiler.parser.antlr.AntlrParserFacadeImpl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportResolutionPassTest {

    private fun runPass(
        rootDir: Path,
        entryFile: String,
    ): Result {
        val parser = AntlrParserFacadeImpl()
        val cache = ModuleCompilationCache()
        val messages = MessageCollector()
        val pass = ImportResolutionPass(parser, cache, messages)

        val entryPath = rootDir.resolve(entryFile)
        val source = Source(entryPath.toString(), entryPath.toFile().readText())
        val parsed = parser.parse(source, messages)
        val transformed = pass.resolve(parsed, entryPath)
        return Result(transformed, cache, messages)
    }

    private fun write(dir: Path, name: String, content: String): File {
        val f = dir.resolve(name).toFile()
        f.writeText(content.trimIndent())
        return f
    }

    private data class Result(
        val transformed: ParsedSource,
        val cache: ModuleCompilationCache,
        val messages: MessageCollector,
    )

    /** True when [atom] is a surviving `(import! …)` directive Run. */
    private fun isImportRun(atom: Any?): Boolean =
        atom is Run && (atom.expression.atoms.firstOrNull() as? Symbol)?.name == "import!"

    @Test
    fun `linear import — utils ends up in cache, import! Run kept for runtime`(@TempDir tmp: Path) {
        write(tmp, "utils.metta", "(= (foo) 5)")
        write(tmp, "main.metta", """
            !(import! &self utils)
            (Fact a)
        """)

        val r = runPass(tmp, "main.metta")
        assertTrue(r.messages.list().isEmpty(), "no messages: ${r.messages.list()}")
        assertEquals(1, r.cache.resolved.size)
        // Runtime-ordered import: the import! Run survives (executes at runtime) alongside
        // the (Fact a) atom — it is no longer stripped at compile time.
        assertEquals(2, r.transformed.code.size)
        assertTrue(r.transformed.code.any { isImportRun(it) }, "import! Run should be kept")
        assertTrue(r.transformed.code.any { it is Expression }, "(Fact a) should survive")
    }

    @Test
    fun `transitive — main imports A, A imports B, both end up in cache`(@TempDir tmp: Path) {
        write(tmp, "B.metta", "(= (b) 1)")
        write(tmp, "A.metta", """
            !(import! &self B)
            (= (a) 2)
        """)
        write(tmp, "main.metta", "!(import! &self A)")

        val r = runPass(tmp, "main.metta")
        assertTrue(r.messages.list().isEmpty(), r.messages.list().toString())
        assertEquals(2, r.cache.resolved.size)
        // A's resolved ParsedSource keeps its own import! Run (runtime-ordered import).
        val aResolved = r.cache.resolved.values.first { it.filename.endsWith("A.metta") }
        assertTrue(aResolved.code.any { isImportRun(it) }, "A should keep its import! Run")
    }

    @Test
    fun `diamond — shared module is parsed exactly once`(@TempDir tmp: Path) {
        write(tmp, "C.metta", "(= (c) 0)")
        write(tmp, "A.metta", "!(import! &self C)")
        write(tmp, "B.metta", "!(import! &self C)")
        write(tmp, "main.metta", """
            !(import! &self A)
            !(import! &self B)
        """)

        val r = runPass(tmp, "main.metta")
        assertTrue(r.messages.list().isEmpty(), r.messages.list().toString())
        // A, B, C — three modules, each exactly once.
        assertEquals(3, r.cache.resolved.size)
        val cKey = r.cache.resolved.keys.first { it.toString().endsWith("C.metta") }
        assertEquals(1, r.cache.resolved.keys.count { it == cKey })
    }

    @Test
    fun `cycle — A imports B imports A is rejected with CyclicImportMessage`(@TempDir tmp: Path) {
        write(tmp, "A.metta", "!(import! &self B)")
        write(tmp, "B.metta", "!(import! &self A)")

        val r = runPass(tmp, "A.metta")
        assertTrue(
            r.messages.list().any { it is CyclicImportMessage },
            "expected CyclicImportMessage in: ${r.messages.list()}"
        )
    }

    @Test
    fun `self-cycle — module imports itself`(@TempDir tmp: Path) {
        write(tmp, "self.metta", "!(import! &self self)")

        val r = runPass(tmp, "self.metta")
        assertTrue(r.messages.list().any { it is CyclicImportMessage })
    }

    @Test
    fun `missing module produces MissingModuleMessage and continues`(@TempDir tmp: Path) {
        write(tmp, "main.metta", """
            !(import! &self does-not-exist)
            (Fact b)
        """)

        val r = runPass(tmp, "main.metta")
        assertTrue(r.messages.list().any { it is MissingModuleMessage })
        // Surviving atoms: the import! Run (kept uniformly, even on error — it is a runtime
        // no-op when the module is absent) plus (Fact b).
        assertEquals(2, r.transformed.code.size)
        assertTrue(r.transformed.code.any { it is Expression })
    }

    @Test
    fun `non-self target is accepted — module imported into a named space`(@TempDir tmp: Path) {
        // A named target space (`&kb`) is now supported: the module is compiled and its
        // import edge recorded, and the runtime `import!` loads it into that space. No
        // ImportAsNotImplemented error; the space name is carried by the surviving Run.
        write(tmp, "something.metta", "(Fact x)")
        write(tmp, "main.metta", "!(import! &kb something)")

        val r = runPass(tmp, "main.metta")
        assertTrue(r.messages.list().isEmpty(), "no messages: ${r.messages.list()}")
        assertEquals(1, r.cache.resolved.size)
        assertTrue(r.transformed.code.any { isImportRun(it) }, "import! &kb Run should be kept")
    }

    @Test
    fun `module name with bang suffix is rejected`(@TempDir tmp: Path) {
        // `foo!` lexes fine as a single IDENT after the grammar relax, but it isn't a
        // safe filename — the pass's regex check rejects it before filesystem access.
        write(tmp, "main.metta", "!(import! &self foo!)")

        val r = runPass(tmp, "main.metta")
        assertTrue(
            r.messages.list().any { it is InvalidModuleNameMessage },
            "expected InvalidModuleNameMessage, got: ${r.messages.list()}"
        )
    }

    @Test
    fun `imported runs are spliced at the import position on first load`(@TempDir tmp: Path) {
        write(tmp, "utils.metta", "!(println from-utils)")
        write(tmp, "main.metta", """
            !(println before)
            !(import! &self utils)
            !(println after)
        """)

        val r = runPass(tmp, "main.metta")
        assertTrue(r.messages.list().isEmpty(), r.messages.list().toString())
        // Effect Runs in order: before, from-utils (spliced), after. The surviving
        // `import!` directive Run sits between `before` and `from-utils`; filter it out to
        // check the load-time-effect order.
        val effectRuns = r.transformed.code.filterIsInstance<Run>().filterNot { isImportRun(it) }
        val texts = effectRuns.map { (it.expression.atoms[1] as Symbol).name }
        assertEquals(listOf("before", "from-utils", "after"), texts)
    }

    @Test
    fun `diamond - shared module's runs are spliced exactly once`(@TempDir tmp: Path) {
        // C has a Run; A and B both import C; main imports A and B.
        // C's Run must appear in main's transformed source exactly once
        // (idempotent load: subsequent imports of an already-loaded module
        // are no-ops for `!`-Runs).
        write(tmp, "C.metta", "!(println from-C)")
        write(tmp, "A.metta", "!(import! &self C)")
        write(tmp, "B.metta", "!(import! &self C)")
        write(tmp, "main.metta", """
            !(import! &self A)
            !(import! &self B)
        """)

        val r = runPass(tmp, "main.metta")
        assertTrue(r.messages.list().isEmpty(), r.messages.list().toString())
        // Ignore the surviving `import!` directive Runs (there is one per import site, all
        // idempotent at runtime); C's load-time effect run must appear exactly once.
        val effectRuns = r.transformed.code.filterIsInstance<Run>().filterNot { isImportRun(it) }
        assertEquals(1, effectRuns.size, "expected C's run exactly once, got: $effectRuns")
        assertEquals("from-C", (effectRuns[0].expression.atoms[1] as Symbol).name)
    }

    @Test
    fun `order is preserved — atoms before and after import survive in their order`(@TempDir tmp: Path) {
        write(tmp, "M.metta", "(= (m) 0)")
        write(tmp, "main.metta", """
            (Fact a)
            !(import! &self M)
            (Fact b)
        """)

        val r = runPass(tmp, "main.metta")
        assertTrue(r.messages.list().isEmpty(), r.messages.list().toString())
        // import! removed; (Fact a) and (Fact b) preserved in order.
        val symbols = r.transformed.code.filterIsInstance<Expression>()
            .map { (it.atoms[0] as Symbol).name }
        assertEquals(listOf("Fact", "Fact"), symbols)
    }

    /**
     * A module's load-time effects belong to the MODULE's space, not the importer's. Spliced
     * verbatim, `mid`'s own `!(import! &self inner)` ran against the importer at run time — so
     * `inner`'s atoms landed in `main`'s `&self` even though `main` only imported `mid` into a
     * NAMED space. That is f1_imports' `g`, reachable from line 1. `&self` is rebound to the
     * module's own name as the Run is spliced.
     */
    @Test
    fun `a spliced run's &self is rebound to the module's own space`(@TempDir tmp: Path) {
        write(tmp, "inner.metta", "(= (bar) 7)")
        write(tmp, "mid.metta", """
            !(import! &self inner)
            (= (foo) 5)
        """)
        write(tmp, "main.metta", "!(import! &m mid)")

        val r = runPass(tmp, "main.metta")
        assertTrue(r.messages.list().isEmpty(), r.messages.list().toString())

        val imports = r.transformed.code.filter { isImportRun(it) }.map { it as Run }
        // main's own directive, unchanged, then mid's spliced one with `&self` -> `mid`.
        assertEquals(2, imports.size, "expected main's import! plus mid's spliced one")
        assertEquals("&m", (imports[0].expression.atoms[1] as Symbol).name)
        assertEquals("mid", (imports[1].expression.atoms[1] as Symbol).name)
        assertEquals("inner", (imports[1].expression.atoms[2] as Symbol).name)
    }
}
