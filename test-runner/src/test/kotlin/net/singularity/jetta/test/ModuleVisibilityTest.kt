package net.singularity.jetta.test

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Ordered module visibility — the compile-time twin of the runtime watermark.
 *
 * A cross-module symbol means nothing until its module has been imported into `&self`, and
 * `import!` is order-sensitive. `Context.resolvedFunctions` is one flat map filled from every
 * compiled source, so every imported name used to resolve everywhere: `(q 3)` computed 103 no
 * matter where — or whether — the module was imported. That is f1_imports :56, where the expected
 * `(g 3)` must stay inert because moduleA reaches `&self` only at :61.
 *
 * These need two files, so they run through [JettaTestRunner] (compile + run + classify); the
 * imported helper is not a separate entry, and a PASS means every `!`-assertion in the file held.
 */
class ModuleVisibilityTest {

    private val helper = """
        (: q (-> Number Number))
        (= (q ${'$'}x) (+ ${'$'}x 100))
    """.trimIndent() + "\n"

    private fun runOne(dir: File, name: String, body: String): ReportEntry {
        File(dir, "vismod.metta").writeText(helper)
        File(dir, "$name.metta").writeText(body.trimIndent() + "\n")
        val summary = JettaTestRunner().run(dir, emptyMap())
        return summary.entries.single { it.file.startsWith(name) }
    }

    private fun assertPasses(entry: ReportEntry) =
        assertEquals(
            TestStatus.PASS, entry.status,
            "output:\n${entry.output}\nmessage: ${entry.message}"
        )

    /** No import at all: the name is data, and stays so. This already held. */
    @Test
    fun `an unimported module's function is inert`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "VisNone",
                $$"""
                    !(assertEqualToResult (q 3) ((q 3)))
                """
            )
        )
    }

    /** After `import! &self`, the same call is a real call. */
    @Test
    fun `an imported module's function is callable`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "VisSelf",
                $$"""
                    !(import! &self vismod)
                    !(assertEqual (q 3) 103)
                """
            )
        )
    }

    /** ORDER: the same file, both sides of its own import. */
    @Test
    fun `visibility begins at the import, not at the top of the file`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "VisOrder",
                $$"""
                    !(assertEqualToResult (q 3) ((q 3)))
                    !(import! &self vismod)
                    !(assertEqual (q 3) 103)
                """
            )
        )
    }

    /** TARGET: importing into a NAMED space does not make the module callable from `&self`. */
    @Test
    fun `importing into a named space does not make the module callable`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "VisNamed",
                $$"""
                    !(import! &m vismod)
                    !(assertEqualToResult (q 3) ((q 3)))
                """
            )
        )
    }
}
