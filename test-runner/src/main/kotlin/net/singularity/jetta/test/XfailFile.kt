package net.singularity.jetta.test

import java.io.File

/**
 * Reads the `xfail` file — a flat list of tests that are expected to fail at present.
 *
 * The format is line-based to keep the file diffable with no parser dependency:
 * ```
 *   # comments start with hash; blank lines ignored
 *   <relative-path>  <STATUS>:<CATEGORY>:<id>  [free-form notes]
 * ```
 * Where `STATUS` matches one of the [TestStatus] values (sans `PASS`), and the rest of the
 * `:`-separated tail is informational. Multiple whitespace separators are tolerated; the
 * notes section is anything past the second whitespace-delimited token.
 *
 * Malformed lines are logged to the supplied [log] sink and skipped so a single typo
 * doesn't take the whole runner down. Duplicate file entries log a warning and the last
 * occurrence wins.
 */
object XfailFile {

    /**
     * Parse [file] into a `path → entry` map. A null or non-existent file is treated as
     * an empty list (no expected failures). Errors during parsing are reported via [log]
     * but never thrown.
     */
    fun load(file: File?, log: (String) -> Unit = { System.err.println(it) }): Map<String, XfailEntry> {
        if (file == null || !file.exists()) return emptyMap()
        val out = mutableMapOf<String, XfailEntry>()
        file.useLines { lines ->
            lines.forEachIndexed { idx, raw ->
                val parsed = parseLine(raw, idx + 1, file.path, log) ?: return@forEachIndexed
                if (out.containsKey(parsed.file)) {
                    log("xfail: duplicate entry for ${parsed.file} at ${file.path}:${idx + 1} (overriding)")
                }
                out[parsed.file] = parsed
            }
        }
        return out
    }

    /**
     * Parse one raw line. Returns `null` if the line is blank, a comment, or so malformed
     * that nothing useful can be extracted (after logging). The line number and file path
     * passed in are only used to make warning messages precise.
     */
    private fun parseLine(raw: String, lineNo: Int, source: String, log: (String) -> Unit): XfailEntry? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val tokens = trimmed.split("\\s+".toRegex(), limit = 3)
        if (tokens.size < 2) {
            log("xfail: malformed line at $source:$lineNo (need at least <file> <status:reason>): $raw")
            return null
        }
        val filename = tokens[0]
        val codeToken = tokens[1]
        val notes = if (tokens.size == 3) tokens[2] else ""
        val colon = codeToken.indexOf(':')
        if (colon < 0) {
            log("xfail: missing ':' in reason code at $source:$lineNo: $codeToken")
            return null
        }
        val statusName = codeToken.substring(0, colon)
        val reasonRest = codeToken.substring(colon + 1)
        val status = try {
            TestStatus.valueOf(statusName)
        } catch (_: IllegalArgumentException) {
            log("xfail: unknown status '$statusName' at $source:$lineNo (expected one of: ${TestStatus.values().joinToString()})")
            return null
        }
        // PASS is meaningless as an "expected failure" — silently passing tests don't need an entry,
        // and "expected to pass but actually failing" is exactly what UNEXPECTED_FAIL is for.
        if (status == TestStatus.PASS) {
            log("xfail: status PASS makes no sense at $source:$lineNo (use empty xfail to expect pass)")
            return null
        }
        return XfailEntry(filename, status, reasonRest, notes)
    }
}
