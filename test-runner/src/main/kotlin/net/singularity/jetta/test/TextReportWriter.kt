package net.singularity.jetta.test

import java.io.File
import java.time.Instant

/**
 * Renders a human-readable run summary as plain text.
 *
 * The shape of the report is "headlines first, detail on demand": a top block with totals,
 * then sections that only enumerate the entries the developer should look at — unexpected
 * failures, regressions, unexpected passes. Expected failures and passes are counted but
 * not enumerated; the JUnit XML alongside has the full per-entry detail for IDE integration.
 */
object TextReportWriter {

    /** Convenience: format and write to [target], creating parent directories if needed. */
    fun write(summary: RunSummary, suiteRoot: File, target: File) {
        target.parentFile?.mkdirs()
        target.writeText(format(summary, suiteRoot))
    }

    /** Render the report as a single string. Pure function; safe to call without I/O. */
    fun format(summary: RunSummary, suiteRoot: File): String = buildString {
        appendLine("MeTTa compatibility report")
        appendLine("Suite: ${suiteRoot.absolutePath}")
        appendLine("Generated: ${Instant.now()}")
        appendLine("Total entries: ${summary.total}")
        appendLine()
        appendLine("Summary:")
        appendLine("  PASS:              ${pad(summary.pass)}")
        appendLine("  EXPECTED_FAIL:     ${pad(summary.expectedFail)}   (covered by xfail)")
        appendLine("  UNEXPECTED_FAIL:   ${pad(summary.unexpectedFail)}${alertMark(summary.unexpectedFail)}")
        appendLine("  UNEXPECTED_PASS:   ${pad(summary.unexpectedPass)}${alertMark(summary.unexpectedPass)}")
        appendLine("  REGRESSION:        ${pad(summary.regression)}${alertMark(summary.regression)}")
        appendLine()

        section("UNEXPECTED FAILURES",
            summary.entries.filter { it.classification == Classification.UNEXPECTED_FAIL })
        section("REGRESSIONS",
            summary.entries.filter { it.classification == Classification.REGRESSION },
            showXfailReason = true)
        section("UNEXPECTED PASSES",
            summary.entries.filter { it.classification == Classification.UNEXPECTED_PASS },
            showXfailReason = true)

        if (summary.expectedFail > 0) appendLine("(${summary.expectedFail} expected failures elided — see junit.xml)")
        if (summary.pass > 0) appendLine("(${summary.pass} passes elided)")
    }

    /**
     * Emit one labelled section. Empty sections still print "(none)" so the reader can tell
     * the section was considered (vs accidentally omitted by a bug).
     *
     * Set [showXfailReason] when the section's interest is the *delta* from the xfail entry —
     * regressions and unexpected passes both want to surface what was predicted.
     */
    private fun StringBuilder.section(
        title: String,
        rows: List<ReportEntry>,
        showXfailReason: Boolean = false,
    ) {
        appendLine("=== $title ===")
        appendLine()
        if (rows.isEmpty()) {
            appendLine("  (none)")
            appendLine()
            return
        }
        rows.forEach { entry ->
            val xfailNote = if (showXfailReason && entry.xfailReason != null) "  (xfail expected ${entry.xfailReason})" else ""
            appendLine("[${entry.file}]  ${entry.status}$xfailNote")
            entry.message.lines().forEach { appendLine("  $it") }
            if (entry.output.isNotBlank()) {
                appendLine("  --- output ---")
                entry.output.lines().forEach { appendLine("  $it") }
            }
            appendLine()
        }
    }

    private fun pad(n: Int): String = n.toString().padStart(4)

    private fun alertMark(n: Int): String = if (n > 0) "   <-- alert" else ""
}
