package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Run
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.rewrite.ImportResolutionPass
import net.singularity.jetta.compiler.frontend.rewrite.ModuleCompilationCache
import net.singularity.jetta.compiler.frontend.rewrite.messages.CyclicImportMessage
import net.singularity.jetta.compiler.frontend.rewrite.messages.ImportAsNotImplementedMessage
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

    @Test
    fun `linear import — utils ends up in cache, importer has no import! Run`(@TempDir tmp: Path) {
        write(tmp, "utils.metta", "(= (foo) 5)")
        write(tmp, "main.metta", """
            !(import! &self utils)
            (Fact a)
        """)

        val r = runPass(tmp, "main.metta")
        assertTrue(r.messages.list().isEmpty(), "no messages: ${r.messages.list()}")
        assertEquals(1, r.cache.resolved.size)
        // import! Run gone, only (Fact a) remains
        assertEquals(1, r.transformed.code.size)
        assertTrue(r.transformed.code[0] is Expression)
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
        // A's resolved ParsedSource should have its import! Run gone.
        val aResolved = r.cache.resolved.values.first { it.filename.endsWith("A.metta") }
        assertTrue(aResolved.code.none { it is Run })
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
        // Surviving atoms: just (Fact b) — import! is dropped even on error.
        assertEquals(1, r.transformed.code.size)
    }

    @Test
    fun `non-self target produces ImportAsNotImplementedMessage`(@TempDir tmp: Path) {
        write(tmp, "main.metta", "!(import! &kb something)")

        val r = runPass(tmp, "main.metta")
        val errs = r.messages.list().filterIsInstance<ImportAsNotImplementedMessage>()
        assertEquals(1, errs.size)
        assertEquals("&kb", errs[0].targetName)
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
}
