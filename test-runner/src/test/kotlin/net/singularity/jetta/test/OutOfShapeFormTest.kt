package net.singularity.jetta.test

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Forms that are ordinary MeTTa but do not fit the shape a compiler pass destructures.
 *
 * Each of these CRASHED the compiler — not "compiled to something wrong", but threw out of a
 * rewriter or the resolver, so the whole file produced nothing. They were found by running the
 * 124-program MeTTa-TS corpus, where they accounted for three separate crashes.
 *
 * Note what these tests do and do not claim. A partially applied operator still does not REDUCE
 * once completed (`((== 1) 1)` stays inert instead of yielding True) — that is a separate, open
 * gap in variable-head dispatch. What is pinned here is that the form is accepted and left as
 * data instead of killing the compile, so each program below also carries one assertion that
 * genuinely holds, to keep the test from passing vacuously.
 */
class OutOfShapeFormTest {

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

    /**
     * The empty expression `()` is MeTTa's nil-like value and a routine operand. `isFunctionCall`
     * read `atoms[0]` off it (IndexOutOfBounds), and once past that, lifting the enclosing
     * non-deterministic `if` arm hit an untyped capture (`type!!`).
     */
    @Test
    fun `the empty expression is a legal operand`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "EmptyOperand",
                $$"""
                    (= (isnil $l) (if (== $l ()) True False))
                    !(assertEqual (isnil ()) True)
                    !(assertEqual (isnil (1 2)) False)
                """
            )
        )
    }

    /**
     * A grounded operator handed fewer operands than it takes is a partial application, normally
     * on its way to a higher-order function: `(mymap (== 1) …)`. Every `Special` branch in the
     * resolver destructures a fixed shape, so `(== 1)` threw and `(+ 2)` reached codegen and
     * threw there.
     */
    @Test
    fun `a partially applied grounded operator does not crash the compiler`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "PartialOp",
                $$"""
                    (= (hold $f) $f)
                    !(assertEqual (+ 1 2) 3)
                    !(hold (== 1))
                    !(hold (+ 2))
                    !(hold (if True))
                """
            )
        )
    }

    /** The same partial applications inside a data constructor, where the corpus meets them. */
    @Test
    fun `a partially applied operator survives as an argument`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "PartialArg",
                $$"""
                    (= (mymap $f ()) ())
                    (= (mymap $f (cons $x $xs)) (cons ($f $x) (mymap $f $xs)))
                    !(assertEqual (+ 1 2) 3)
                    !(mymap (== 1) (1 2 3))
                """
            )
        )
    }
}
