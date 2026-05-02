package net.singularity.jetta.test

/**
 * Outcome of running a single `.metta` file.
 *
 * The four values are deliberately distinct (rather than collapsed into a boolean)
 * because each tells a different story to the developer: a compile failure points
 * at the parser/resolver, an `AssertionError` from `assertEqual*` points at semantics,
 * and a runtime exception points at codegen or the standard library.
 */
enum class TestStatus {
    /** The compiled program ran to completion without throwing. */
    PASS,

    /** The program threw an [AssertionError], typically from `assertEqual` / `assertEqualToResult`. */
    ASSERT_FAIL,

    /** The program threw a non-assertion `Throwable` while executing — NPE, ClassCastException, etc. */
    RUN_EXCEPTION,

    /** The compiler returned a non-zero exit code or threw before producing class files. */
    COMPILE_FAIL,
}

/**
 * The classification of a single test run after cross-referencing the actual outcome
 * with the project's `xfail` list.
 *
 * The runner uses this to decide whether to alert the developer:
 *   * [PASS], [EXPECTED_FAIL] — silent (no action needed).
 *   * [UNEXPECTED_FAIL], [UNEXPECTED_PASS], [REGRESSION] — alert (developer should look).
 */
enum class Classification {
    /** The test passed and was not marked as expected to fail. */
    PASS,

    /** The test failed, and the `xfail` entry predicted exactly this kind of failure. Silent. */
    EXPECTED_FAIL,

    /** The test failed without an `xfail` entry covering it. */
    UNEXPECTED_FAIL,

    /** The test passed but an `xfail` entry expected it to fail. The entry should be removed. */
    UNEXPECTED_PASS,

    /** The test failed, an `xfail` entry covers it, but the failure mode is different from the prediction. */
    REGRESSION,
}

/**
 * One parsed line from the `xfail` file.
 *
 * The runner uses [expectedStatus] (and only that field) to decide between [Classification.EXPECTED_FAIL]
 * and [Classification.REGRESSION]. The rest is informational for humans reading the report.
 *
 * @property file Test file name relative to the suite root, exactly as it appears in the `xfail` line.
 * @property expectedStatus The status the developer expects this test to currently produce.
 * @property reasonCode The `<CATEGORY>:<id>` portion after the status (e.g. `FEATURE_PENDING:import-resolver`).
 * @property notes Free-form trailing text, may be empty. Surfaces in reports for context.
 */
data class XfailEntry(
    val file: String,
    val expectedStatus: TestStatus,
    val reasonCode: String,
    val notes: String,
)

/**
 * One row in the test report — the merged view of an actual run plus its `xfail` cross-reference.
 *
 * @property file Test file name relative to the suite root.
 * @property status Actual outcome of running the test.
 * @property classification How the runner judged the outcome (alert vs. silent).
 * @property message Short, single-line summary of the failure (or `"OK"` when passing).
 * @property output Combined stdout/stderr captured while compiling and running. May be multi-line and large.
 * @property xfailReason `STATUS:CATEGORY:id` token from the matching `xfail` line, or `null` if none.
 */
data class ReportEntry(
    val file: String,
    val status: TestStatus,
    val classification: Classification,
    val message: String,
    val output: String,
    val xfailReason: String?,
)

/**
 * Aggregated outcome of a complete runner invocation.
 *
 * Fields are computed lazily from [entries]; recomputing on each access keeps construction cheap
 * and ensures totals never disagree with the entry list.
 */
data class RunSummary(
    val entries: List<ReportEntry>,
) {
    val total: Int get() = entries.size
    val pass: Int get() = entries.count { it.classification == Classification.PASS }
    val expectedFail: Int get() = entries.count { it.classification == Classification.EXPECTED_FAIL }
    val unexpectedFail: Int get() = entries.count { it.classification == Classification.UNEXPECTED_FAIL }
    val unexpectedPass: Int get() = entries.count { it.classification == Classification.UNEXPECTED_PASS }
    val regression: Int get() = entries.count { it.classification == Classification.REGRESSION }

    /**
     * `true` when there is at least one entry the developer should investigate —
     * unexpected failures, unexpected passes, or regressions. Used to compute the runner's exit code.
     */
    val hasAlerts: Boolean get() = unexpectedFail > 0 || unexpectedPass > 0 || regression > 0
}

/**
 * Map an actual test outcome plus an optional `xfail` entry to a final classification.
 *
 * Without an `xfail` entry, a passing test is [Classification.PASS] and any failure is
 * [Classification.UNEXPECTED_FAIL]. With an `xfail` entry present, a passing test becomes
 * [Classification.UNEXPECTED_PASS] (the entry is now stale and should be removed), and
 * a failing test is either [Classification.EXPECTED_FAIL] (status agrees) or
 * [Classification.REGRESSION] (status changed — the failure mode shifted).
 *
 * Keeping this as a free function (not a method on [TestStatus] or [XfailEntry]) makes the
 * full decision table easy to test in isolation, see `ClassificationTest`.
 */
fun classify(actual: TestStatus, xfail: XfailEntry?): Classification {
    if (xfail == null) {
        return if (actual == TestStatus.PASS) Classification.PASS else Classification.UNEXPECTED_FAIL
    }
    if (actual == TestStatus.PASS) return Classification.UNEXPECTED_PASS
    return if (xfail.expectedStatus == actual) Classification.EXPECTED_FAIL else Classification.REGRESSION
}
