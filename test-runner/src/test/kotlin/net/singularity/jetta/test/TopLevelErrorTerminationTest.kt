package net.singularity.jetta.test

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A top-level `(Error …)` ends the program, and the runner scores that as a failure.
 *
 * Both halves matter. Verified on `metta-repl`: `!(println! first-ran)` `!(+ 5 "S")`
 * `!(println! third-ran)` prints `first-ran` and the error, and never `third-ran`. And the
 * classification is what keeps termination honest — a file that stops early without throwing
 * would otherwise be indistinguishable from a file that ran to completion, which would INFLATE
 * every corpus measurement taken afterwards.
 */
class TopLevelErrorTerminationTest {

    @Test
    fun `a run below a top-level error never runs`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        File(dir, "Errseq.metta").writeText(
            """
            !(println! first-ran)
            !(+ 5 "S")
            !(println! third-ran)
            """.trimIndent() + "\n"
        )
        val entry = JettaTestRunner().run(dir, emptyMap()).entries.single()
        assertEquals(TestStatus.ERROR_TERM, entry.status, "output:\n${entry.output}\nmessage: ${entry.message}")
        assertEquals(Classification.UNEXPECTED_FAIL, entry.classification)
        assertTrue(entry.output.contains("first-ran"), "the run above the error must have run: ${entry.output}")
        assertFalse(entry.output.contains("third-ran"), "the run below the error must NOT have run: ${entry.output}")
        assertTrue(entry.message.contains("BadArgType"), "the error term is the message: ${entry.message}")
    }

    /** The last run is checked too: the error is the program's visible outcome, not just a stop. */
    @Test
    fun `a trailing top-level error fails the file`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        File(dir, "Errlast.metta").writeText("!(println! ran)\n!(+ 5 \"S\")\n")
        val entry = JettaTestRunner().run(dir, emptyMap()).entries.single()
        assertEquals(TestStatus.ERROR_TERM, entry.status, "output:\n${entry.output}\nmessage: ${entry.message}")
    }

    /** An `xfail` line may predict it, exactly like the other failure kinds. */
    @Test
    fun `an xfail entry covers an ERROR_TERM file`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        File(dir, "Errseq.metta").writeText("!(+ 5 \"S\")\n")
        val xfail = mapOf(
            "Errseq.metta" to XfailEntry("Errseq.metta", TestStatus.ERROR_TERM, "TEST:foo", ""),
        )
        val summary = JettaTestRunner().run(dir, xfail)
        assertEquals(Classification.EXPECTED_FAIL, summary.entries.single().classification)
        assertFalse(summary.hasAlerts)
    }

    /**
     * An error a program builds and passes around is DATA: only an error in the run's own result
     * position ends it. Here the error sits inside a term, and the run below it still runs.
     */
    @Test
    fun `an error nested inside a result does not terminate`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        File(dir, "Errnested.metta").writeText(
            """
            !(println! (wrapped (+ 5 "S")))
            !(println! still-running)
            """.trimIndent() + "\n"
        )
        val entry = JettaTestRunner().run(dir, emptyMap()).entries.single()
        assertEquals(TestStatus.PASS, entry.status, "output:\n${entry.output}\nmessage: ${entry.message}")
        assertTrue(entry.output.contains("still-running"), entry.output)
    }
}
