package net.singularity.jetta.test

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

class JettaTestRunnerSyntheticTest {

    @Test
    fun `synthetic PASS test is classified as PASS`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        File(dir, "Pass.metta").writeText("!(println! 1)\n")
        val summary = JettaTestRunner().run(dir, emptyMap())
        assertEquals(1, summary.entries.size)
        val entry = summary.entries.single()
        assertEquals(TestStatus.PASS, entry.status, "output:\n${entry.output}\nmessage: ${entry.message}")
        assertEquals(Classification.PASS, entry.classification)
    }

    @Test
    fun `synthetic ASSERT_FAIL test is classified correctly`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        File(dir, "Assertfail.metta").writeText("!(assertEqual 1 2)\n")
        val summary = JettaTestRunner().run(dir, emptyMap())
        val entry = summary.entries.single()
        assertEquals(TestStatus.ASSERT_FAIL, entry.status, "output:\n${entry.output}\nmessage: ${entry.message}")
        assertEquals(Classification.UNEXPECTED_FAIL, entry.classification)
    }

    @Test
    fun `synthetic COMPILE_FAIL test is classified correctly`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        File(dir, "Compilefail.metta").writeText("(((\n")
        val summary = JettaTestRunner().run(dir, emptyMap())
        val entry = summary.entries.single()
        assertEquals(TestStatus.COMPILE_FAIL, entry.status)
        assertEquals(Classification.UNEXPECTED_FAIL, entry.classification)
    }

    @Test
    fun `xfail entry flips classification to EXPECTED_FAIL`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        File(dir, "Assertfail.metta").writeText("!(assertEqual 1 2)\n")
        val xfail = mapOf(
            "Assertfail.metta" to XfailEntry(
                "Assertfail.metta", TestStatus.ASSERT_FAIL, "TEST:foo", "",
            )
        )
        val summary = JettaTestRunner().run(dir, xfail)
        assertEquals(Classification.EXPECTED_FAIL, summary.entries.single().classification)
        assertEquals(false, summary.hasAlerts)
    }

    @Test
    fun `xfail entry with wrong expected status is REGRESSION`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        File(dir, "Assertfail.metta").writeText("!(assertEqual 1 2)\n")
        val xfail = mapOf(
            "Assertfail.metta" to XfailEntry(
                "Assertfail.metta", TestStatus.COMPILE_FAIL, "TEST:foo", "",
            )
        )
        val summary = JettaTestRunner().run(dir, xfail)
        assertEquals(Classification.REGRESSION, summary.entries.single().classification)
        assertEquals(true, summary.hasAlerts)
    }

    @Test
    fun `xfail on passing test is UNEXPECTED_PASS`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        File(dir, "Pass.metta").writeText("!(println! 1)\n")
        val xfail = mapOf(
            "Pass.metta" to XfailEntry(
                "Pass.metta", TestStatus.ASSERT_FAIL, "TEST:foo", "",
            )
        )
        val summary = JettaTestRunner().run(dir, xfail)
        assertEquals(Classification.UNEXPECTED_PASS, summary.entries.single().classification)
        assertEquals(true, summary.hasAlerts)
    }

    @Test
    fun `empty directory yields zero entries`(@TempDir tmp: Path) {
        val summary = JettaTestRunner().run(tmp.toFile(), emptyMap())
        assertEquals(0, summary.entries.size)
        assertEquals(false, summary.hasAlerts)
    }

    @Test
    fun `module-only files are skipped as standalone entries`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        File(dir, "main.metta").writeText("!(import! &self utils)\n!(println! hello)\n")
        File(dir, "utils.metta").writeText("(Fact a)\n")
        File(dir, "standalone.metta").writeText("!(println! world)\n")

        val summary = JettaTestRunner().run(dir, emptyMap())
        // utils is imported by main; only main and standalone run as entries.
        val files = summary.entries.map { it.file }.toSet()
        assertEquals(setOf("main.metta", "standalone.metta"), files)
    }
}
