package net.singularity.jetta.test

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportGraphTest {

    private fun write(dir: Path, name: String, content: String): File {
        val f = dir.resolve(name).toFile()
        f.writeText(content.trimIndent())
        return f
    }

    @Test
    fun `empty file list yields empty set`() {
        assertTrue(ImportGraph.importedNames(emptyList()).isEmpty())
    }

    @Test
    fun `file with no imports contributes nothing`(@TempDir tmp: Path) {
        val f = write(tmp, "a.metta", "(Fact apple)")
        assertTrue(ImportGraph.importedNames(listOf(f)).isEmpty())
    }

    @Test
    fun `single self-import is detected`(@TempDir tmp: Path) {
        val f = write(tmp, "main.metta", "!(import! &self utils)")
        assertEquals(setOf("utils"), ImportGraph.importedNames(listOf(f)))
    }

    @Test
    fun `non-self import is also counted`(@TempDir tmp: Path) {
        // `&kb` doesn't compile yet but it is still an import-by-name; the runner cares
        // whether a file is referenced, not which space-ref it uses.
        val f = write(tmp, "main.metta", "!(import! &kb other)")
        assertEquals(setOf("other"), ImportGraph.importedNames(listOf(f)))
    }

    @Test
    fun `import inside a comment is ignored`(@TempDir tmp: Path) {
        val f = write(tmp, "main.metta", """
            ; !(import! &self ghost)
            (Fact a)
        """)
        assertTrue(ImportGraph.importedNames(listOf(f)).isEmpty())
    }

    @Test
    fun `unparseable file is silently skipped`(@TempDir tmp: Path) {
        val ok = write(tmp, "ok.metta", "!(import! &self utils)")
        val broken = write(tmp, "broken.metta", "(((")
        // The broken file shouldn't crash discovery; the good one still contributes.
        assertEquals(setOf("utils"), ImportGraph.importedNames(listOf(broken, ok)))
    }

    @Test
    fun `multiple files with overlapping imports merge`(@TempDir tmp: Path) {
        val a = write(tmp, "a.metta", "!(import! &self c)")
        val b = write(tmp, "b.metta", """
            !(import! &self c)
            !(import! &self d)
        """)
        assertEquals(setOf("c", "d"), ImportGraph.importedNames(listOf(a, b)))
    }

    @Test
    fun `entry-only file is NOT in the imported set`(@TempDir tmp: Path) {
        // main is the entry; utils is the module. Only utils should appear.
        val main = write(tmp, "main.metta", "!(import! &self utils)")
        val utils = write(tmp, "utils.metta", "(Fact a)")
        val imported = ImportGraph.importedNames(listOf(main, utils))
        assertTrue("utils" in imported)
        assertFalse("main" in imported)
    }
}
