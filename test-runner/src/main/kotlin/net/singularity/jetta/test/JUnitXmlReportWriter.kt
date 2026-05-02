package net.singularity.jetta.test

import java.io.File

/**
 * Renders a run summary as JUnit XML — the dialect IntelliJ, Gradle test reporters, and most
 * CI tools understand. With this in place, opening `junit.xml` from an IDE shows the full
 * per-entry detail in the same panel used for native Kotlin/Java tests.
 *
 * The mapping is:
 *   * PASS                — empty `<testcase>`.
 *   * EXPECTED_FAIL       — `<skipped>` with the xfail reason as the message.
 *   * UNEXPECTED_FAIL     — `<failure>` for assertion failures, `<error>` for compile/runtime exceptions.
 *   * UNEXPECTED_PASS     — `<failure>` (we want it surfaced as red so the stale xfail entry gets removed).
 *   * REGRESSION          — `<failure>` or `<error>` (per underlying status), with the prediction in the message.
 *
 * `<skipped>` for `EXPECTED_FAIL` is a slight semantic stretch — the test ran and failed
 * expectedly — but it's the closest standard element and keeps our exit-code policy decoupled
 * from the JUnit consumer's policy.
 *
 * The writer is hand-rolled to avoid pulling a serialization dependency for a tiny output.
 * It escapes XML metacharacters and strips ASCII control bytes to keep the document well-formed
 * even when test output contains binary noise.
 */
object JUnitXmlReportWriter {

    /** Convenience: format and write to [target], creating parent directories if needed. */
    fun write(summary: RunSummary, target: File, suiteName: String = "metta-compat") {
        target.parentFile?.mkdirs()
        target.writeText(format(summary, suiteName))
    }

    /** Render the run summary as a JUnit XML document. Pure function; safe to call without I/O. */
    fun format(summary: RunSummary, suiteName: String): String {
        val failures = summary.entries.count { it.isFailureForJunit() }
        val errors = summary.entries.count { it.isErrorForJunit() }
        val skipped = summary.expectedFail
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<testsuite name=\"").append(esc(suiteName)).append("\"")
        sb.append(" tests=\"").append(summary.total).append("\"")
        sb.append(" failures=\"").append(failures).append("\"")
        sb.append(" errors=\"").append(errors).append("\"")
        sb.append(" skipped=\"").append(skipped).append("\"")
        sb.append(">\n")

        summary.entries.forEach { entry ->
            sb.append("  <testcase classname=\"metta\" name=\"").append(esc(entry.file)).append("\">\n")
            when (entry.classification) {
                Classification.PASS -> { /* No child element — JUnit treats absence of failure/error/skipped as pass. */ }
                Classification.EXPECTED_FAIL -> {
                    sb.append("    <skipped message=\"")
                        .append(esc("xfail: ${entry.xfailReason ?: "?"}"))
                        .append("\"/>\n")
                }
                Classification.UNEXPECTED_PASS -> {
                    sb.append("    <failure type=\"UnexpectedPass\" message=\"")
                        .append(esc("xfail entry should be removed (test now passes)"))
                        .append("\">").append(esc(entry.output)).append("</failure>\n")
                }
                Classification.UNEXPECTED_FAIL -> {
                    sb.append(emitFailureOrError(entry, expectedNote = null))
                }
                Classification.REGRESSION -> {
                    sb.append(emitFailureOrError(
                        entry,
                        expectedNote = "xfail expected ${entry.xfailReason}, got ${entry.status}",
                    ))
                }
            }
            sb.append("  </testcase>\n")
        }

        sb.append("</testsuite>\n")
        return sb.toString()
    }

    /**
     * Emit a `<failure>` (for assertion problems) or `<error>` (for crashes / compile failures).
     *
     * Most test frameworks distinguish "the test asserted something false" (failure) from
     * "the test infrastructure or code threw" (error). We follow that convention so IDE
     * test panels colour and group the entries the way developers already expect.
     */
    private fun emitFailureOrError(entry: ReportEntry, expectedNote: String?): String {
        val tag = if (entry.status == TestStatus.ASSERT_FAIL) "failure" else "error"
        val type = when (entry.status) {
            TestStatus.ASSERT_FAIL -> "AssertionError"
            TestStatus.RUN_EXCEPTION -> "RuntimeException"
            TestStatus.COMPILE_FAIL -> "CompileError"
            TestStatus.PASS -> "Unexpected"
        }
        val messageText = listOfNotNull(expectedNote, entry.message.takeIf { it.isNotBlank() })
            .joinToString(" | ")
        val body = stripNonPrintable(entry.output)
        return buildString {
            append("    <").append(tag)
                .append(" type=\"").append(esc(type)).append("\"")
                .append(" message=\"").append(esc(messageText)).append("\"")
                .append(">")
            append(esc(body))
            append("</").append(tag).append(">\n")
        }
    }

    /** Whether this entry should be counted under the testsuite's `failures` attribute. */
    private fun ReportEntry.isFailureForJunit(): Boolean =
        classification == Classification.UNEXPECTED_FAIL && status == TestStatus.ASSERT_FAIL ||
                classification == Classification.REGRESSION ||
                classification == Classification.UNEXPECTED_PASS

    /** Whether this entry should be counted under the testsuite's `errors` attribute. */
    private fun ReportEntry.isErrorForJunit(): Boolean =
        classification == Classification.UNEXPECTED_FAIL &&
                (status == TestStatus.RUN_EXCEPTION || status == TestStatus.COMPILE_FAIL)

    /**
     * Escape XML metacharacters and drop ASCII control codes (except whitespace) so the
     * document stays well-formed even when test output contains stray binary bytes.
     */
    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (c.code < 32 && c != '\n' && c != '\r' && c != '\t') {
                // strip control bytes
            } else append(c)
        }
    }

    private fun stripNonPrintable(s: String): String =
        s.filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 }
}
