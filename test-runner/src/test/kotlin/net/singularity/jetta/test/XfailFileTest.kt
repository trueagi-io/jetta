package net.singularity.jetta.test

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XfailFileTest {

    @Test
    fun `parses simple line`(@TempDir tmp: Path) {
        val f = write(tmp, "f1.metta            COMPILE_FAIL:FEATURE_PENDING:import-resolver\n")
        val map = XfailFile.load(f)
        assertEquals(1, map.size)
        val entry = map["f1.metta"]!!
        assertEquals(TestStatus.COMPILE_FAIL, entry.expectedStatus)
        assertEquals("FEATURE_PENDING:import-resolver", entry.reasonCode)
    }

    @Test
    fun `comments and blank lines are skipped`(@TempDir tmp: Path) {
        val f = write(
            tmp,
            """
            # this is a comment

            # another
            x.metta  ASSERT_FAIL:STDLIB_GAP:foo  some notes

            """.trimIndent()
        )
        val map = XfailFile.load(f)
        assertEquals(1, map.size)
        assertEquals("some notes", map["x.metta"]!!.notes)
    }

    @Test
    fun `notes are joined with single space`(@TempDir tmp: Path) {
        val f = write(tmp, "x.metta  RUN_EXCEPTION:KNOWN_BUG:1234   notes with   spaces\n")
        val map = XfailFile.load(f)
        assertEquals("notes with   spaces", map["x.metta"]!!.notes)
    }

    @Test
    fun `malformed line is skipped not fatal`(@TempDir tmp: Path) {
        val f = write(
            tmp,
            """
            valid.metta  ASSERT_FAIL:OK:reason
            justonefield
            ok2.metta  RUN_EXCEPTION:OK:reason
            """.trimIndent()
        )
        val warnings = mutableListOf<String>()
        val map = XfailFile.load(f, log = { warnings.add(it) })
        assertEquals(2, map.size)
        assertTrue(warnings.any { "malformed" in it })
    }

    @Test
    fun `unknown status is skipped with warning`(@TempDir tmp: Path) {
        val f = write(tmp, "x.metta  WHATEVER:foo:bar\n")
        val warnings = mutableListOf<String>()
        val map = XfailFile.load(f, log = { warnings.add(it) })
        assertTrue(map.isEmpty())
        assertTrue(warnings.any { "unknown status" in it })
    }

    @Test
    fun `pass status is rejected`(@TempDir tmp: Path) {
        val f = write(tmp, "x.metta  PASS:foo:bar\n")
        val warnings = mutableListOf<String>()
        val map = XfailFile.load(f, log = { warnings.add(it) })
        assertTrue(map.isEmpty())
        assertTrue(warnings.any { "PASS" in it })
    }

    @Test
    fun `missing file returns empty map`() {
        val map = XfailFile.load(File("/tmp/definitely-not-here-xyz.xfail"))
        assertTrue(map.isEmpty())
    }

    @Test
    fun `null file returns empty map`() {
        val map = XfailFile.load(null)
        assertTrue(map.isEmpty())
    }

    private fun write(tmp: Path, content: String): File {
        val f = tmp.resolve("test.xfail").toFile()
        f.writeText(content)
        return f
    }
}
