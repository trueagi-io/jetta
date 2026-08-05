package net.singularity.jetta.test

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * `quote` suppresses evaluation — that is its whole job.
 *
 * A user-written `quote` used to reduce its argument: `(quote (+ 1 2))` yielded `(quote 3)`. The
 * cause was representational. Internally generated quotes are `PredefinedAtoms.QUOTE`, a
 * `Special`, and every pass keys off that; a `quote` written in the source stayed a `Symbol`, so
 * it fell through the head dispatch to the unresolved-head path — which is the DATA-CONSTRUCTOR
 * path, and a data constructor evaluates its arguments in applicative order.
 *
 * The symptom is visible with nothing but a self-comparison: `!(assertEqual (quote (+ 1 2))
 * (quote (+ 1 2)))` failed, because the assert lowering quotes the expected side (leaving it
 * intact) while the actual side was evaluated.
 *
 * Found in the MeTTa-TS corpus (`he_quoting`, `callquoteevalreduce2`); it breaks anything that
 * builds code as data, which is most of the metacircular-interpreter territory.
 */
class QuoteInertnessTest {

    private fun runOne(dir: File, name: String, body: String): ReportEntry {
        File(dir, "$name.metta").writeText(body.trimIndent() + "\n")
        val summary = JettaTestRunner().run(dir, emptyMap())
        return summary.entries.single { it.file.startsWith(name) }
    }

    private fun assertPasses(entry: ReportEntry) =
        assertEquals(
            TestStatus.PASS, entry.status,
            "output:\n${entry.output}\nmessage: ${entry.message}"
        )

    /** Arithmetic is the case that regressed: `(quote (+ 1 2))` must not become `(quote 3)`. */
    @Test
    fun `a quoted grounded operation is not evaluated`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "QuoteArith",
                $$"""
                    !(assertEqual (quote (+ 1 2)) (quote (+ 1 2)))
                    !(assertEqual (quote (* 3 4)) (quote (* 3 4)))
                """
            )
        )
    }

    /** Nor is a quoted call to a defined function — the same applicative-order path. */
    @Test
    fun `a quoted call to a defined function is not evaluated`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "QuoteCall",
                $$"""
                    (= (inc $x) (+ $x 1))
                    !(assertEqual (quote (inc 5)) (quote (inc 5)))
                    !(assertEqual (inc 5) 6)
                """
            )
        )
    }

    /** Plain symbolic data still quotes as before, and nesting is preserved. */
    @Test
    fun `quoted data and nested quotes are preserved`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "QuoteData",
                $$"""
                    !(assertEqual (quote (foo bar)) (quote (foo bar)))
                    !(assertEqual (quote (quote (+ 1 2))) (quote (quote (+ 1 2))))
                """
            )
        )
    }
}
