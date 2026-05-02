package net.singularity.jetta.test

import java.io.File
import kotlin.system.exitProcess

/**
 * Command-line entry point for the JeTTa compatibility test runner.
 *
 * Usage:
 * ```
 *   jetta-test <test-dir> <report-dir> [--xfail <file>]
 * ```
 *
 * Defaults (when both positional arguments are omitted): `tests/metta` and `tests/reports`.
 *
 * If `--xfail` is not supplied, the runner uses `<test-dir>/.xfail` if it exists, otherwise
 * runs against an empty xfail list. This makes it natural to keep an xfail file co-located
 * with the test directory and pick it up automatically.
 *
 * Exit codes:
 *   * `0` — no UNEXPECTED_FAIL, UNEXPECTED_PASS, or REGRESSION entries.
 *   * `1` — at least one alert was produced; check `report.txt`.
 *   * `2` — the runner couldn't even start (bad arguments, missing test directory, etc.).
 */
fun main(args: Array<String>) {
    val parsed = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        System.err.println("usage: jetta-test <test-dir> <report-dir> [--xfail <file>]")
        exitProcess(2)
    }

    val testDir = File(parsed.testDir)
    val reportDir = File(parsed.reportDir)
    val xfailFile = parsed.xfailFile?.let { File(it) }
        ?: File(testDir, ".xfail").takeIf { it.exists() }

    if (!testDir.isDirectory) {
        System.err.println("error: test directory does not exist: ${testDir.absolutePath}")
        exitProcess(2)
    }

    val xfail = XfailFile.load(xfailFile)
    val summary = JettaTestRunner().run(testDir, xfail)

    TextReportWriter.write(summary, testDir, File(reportDir, "report.txt"))
    JUnitXmlReportWriter.write(summary, File(reportDir, "junit.xml"))

    // Echo the report to stdout so a CI log alone is sufficient to see what happened
    // without separately retrieving the artefact.
    println(TextReportWriter.format(summary, testDir))

    exitProcess(if (summary.hasAlerts) 1 else 0)
}

/** Parsed CLI arguments. Internal to the runner; not part of the public API. */
private data class ParsedArgs(
    val testDir: String,
    val reportDir: String,
    val xfailFile: String?,
)

/**
 * Tiny hand-rolled argument parser — the runner has only three knobs and pulling in a CLI
 * library for them is heavier than it deserves. Throws [IllegalArgumentException] on misuse;
 * the caller is responsible for producing a usage message.
 */
private fun parseArgs(args: Array<String>): ParsedArgs {
    val positional = mutableListOf<String>()
    var xfail: String? = null
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--xfail" -> {
                require(i + 1 < args.size) { "--xfail requires a path argument" }
                xfail = args[i + 1]
                i += 2
            }
            "-h", "--help" -> {
                println("usage: jetta-test <test-dir> <report-dir> [--xfail <file>]")
                kotlin.system.exitProcess(0)
            }
            else -> {
                positional.add(a)
                i += 1
            }
        }
    }
    val (testDir, reportDir) = when (positional.size) {
        0 -> "tests/metta" to "tests/reports"
        2 -> positional[0] to positional[1]
        else -> throw IllegalArgumentException(
            "expected 0 or 2 positional args (test-dir report-dir), got ${positional.size}"
        )
    }
    return ParsedArgs(testDir, reportDir, xfail)
}
