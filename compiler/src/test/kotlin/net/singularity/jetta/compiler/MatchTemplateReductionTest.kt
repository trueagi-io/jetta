package net.singularity.jetta.compiler

import net.singularity.jetta.compiler.logger.LogLevel
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `match` whose TEMPLATE is a grounded-operator expression must be EVALUATED after the
 * bindings are substituted (hyperon semantics), not returned inert. E.g.
 * `(match &self (, (Venus orbit $x au) (Mars orbit $y au)) (- $y $x))` yields `0.8`, not the
 * unreduced `(- 1.5 0.7)`. The rewriter routes such templates to `JettaProgram.matchReduce`.
 * This is the mechanism that closes the last `c2_spaces` assert.
 */
class MatchTemplateReductionTest {

    private fun compileAndRun(@TempDir src: Path, out: Path, program: String, body: String): String {
        File(src.toFile(), "$program.metta").writeText(body.trimIndent())
        val rc = Compiler(
            files = listOf(File(src.toFile(), "$program.metta").absolutePath),
            outputDir = out.toAbsolutePath().toString(),
            logLevel = LogLevel.ERROR,
        ).compile()
        assertEquals(0, rc, "compiler returned $rc")

        // Run in a fresh JVM so the space artifacts are loaded via -Djetta.dataDir (the
        // subprocess cwd is not `out`), mirroring DeepCopyStrategyTest's runner.
        val classpath = "${out.toAbsolutePath()}${File.pathSeparator}${System.getProperty("java.class.path")}"
        val proc = ProcessBuilder("java", "-Djetta.dataDir=${out.toAbsolutePath()}", "-cp", classpath, program)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val rcRun = proc.waitFor()
        assertEquals(0, rcRun, "run exited non-zero. output:\n$output")
        return output
    }

    @Test
    fun `grounded-op match template is reduced over its bindings`(@TempDir src: Path, @TempDir out: Path) {
        val output = compileAndRun(
            src, out, "mtr",
            """
            (Venus orbit 0.7 au)
            (Mars orbit 1.5 au)
            !(println! (collapse (match &self (, (Venus orbit ${'$'}x au) (Mars orbit ${'$'}y au)) (- ${'$'}y ${'$'}x))))
            """
        )
        // The subtraction must be evaluated (0.8), not left inert as (- 1.5 0.7).
        assertTrue(output.contains("0.8"), "expected reduced 0.8; got:\n$output")
        assertTrue(!output.contains("(-"), "template must not remain inert; got:\n$output")
    }

    @Test
    fun `grounded-op match template assertEqual holds`(@TempDir src: Path, @TempDir out: Path) {
        // Fails (non-zero exit via AssertionError) if the template were left unreduced.
        val output = compileAndRun(
            src, out, "mtr2",
            """
            (Venus orbit 0.7 au)
            (Mars orbit 1.5 au)
            !(assertEqual (match &self (, (Venus orbit ${'$'}x au) (Mars orbit ${'$'}y au)) (- ${'$'}y ${'$'}x)) 0.8)
            !(println! "ok")
            """
        )
        assertTrue(output.trim().endsWith("ok"), "assertEqual should hold; got:\n$output")
    }
}
