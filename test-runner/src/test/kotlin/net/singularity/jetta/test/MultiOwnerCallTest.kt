package net.singularity.jetta.test

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * A name defined by MORE THAN ONE imported module is non-deterministic.
 *
 * `import!` copies a module's `(= …)` rules into `&self`, so when two imported modules each
 * carry their own `(= (h $x) …)` the reference interpreter keeps both rules and answers with
 * the UNION of what they reduce to. JeTTa compiles each module to its own class and kept one
 * owner per name (`Context.resolvedFunctions`, last writer wins), so the call compiled to a
 * single `INVOKESTATIC` and only that owner's answer came back — f1_imports :114, where
 * `(dup 2)` gave `[12]` instead of `(12 102)`.
 *
 * Such a call now dispatches over every VISIBLE owner and collects one bag, which also makes it
 * multivalued. The keying is strict — more than one owner, visible HERE — because a single-owner
 * call must stay scalar (f1 :64/:65 assert plain `102`/`103`) and a plain redefinition must not
 * become non-determinism.
 *
 * These need several files, so they run through [JettaTestRunner] (compile + run + classify); an
 * imported helper is not a separate entry, and a PASS means every `!`-assertion in the file held.
 */
class MultiOwnerCallTest {

    /** Untyped, exactly like f1's `dup` — the inferred descriptor is `(I)I` in both modules. */
    private val modOne = "(= (h ${'$'}x) (+ ${'$'}x 10))\n"
    private val modTwo = "(= (h ${'$'}x) (* ${'$'}x 100))\n"

    private fun runOne(dir: File, name: String, body: String): ReportEntry {
        File(dir, "hmodone.metta").writeText(modOne)
        File(dir, "hmodtwo.metta").writeText(modTwo)
        File(dir, "$name.metta").writeText(body.trimIndent() + "\n")
        val summary = JettaTestRunner().run(dir, emptyMap())
        return summary.entries.single { it.file.startsWith(name) }
    }

    private fun assertPasses(entry: ReportEntry) =
        assertEquals(
            TestStatus.PASS, entry.status,
            "output:\n${entry.output}\nmessage: ${entry.message}"
        )

    /** THE CASE: both modules imported into `&self`, so both rules answer. */
    @Test
    fun `a name defined by two imported modules answers with both results`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "UnionBoth",
                $$"""
                    !(import! &self hmodone)
                    !(import! &self hmodtwo)
                    !(assertEqualToResult (h 2) (12 200))
                """
            )
        )
    }

    /** One owner visible ⇒ the call stays SCALAR. This is what keeps f1 :64/:65 at `102`/`103`. */
    @Test
    fun `a name defined by one imported module stays scalar`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "UnionOne",
                $$"""
                    !(import! &self hmodone)
                    !(assertEqual (h 2) 12)
                """
            )
        )
    }

    /**
     * ORDER: the union begins at the second import. Before it there is one visible owner and the
     * call is a plain scalar; after it the same call is a bag. f1's shape exactly — scalar
     * asserts before the diamond, a bag after.
     */
    @Test
    fun `the union begins at the second import`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "UnionOrder",
                $$"""
                    !(import! &self hmodone)
                    !(assertEqual (h 2) 12)
                    !(import! &self hmodtwo)
                    !(assertEqualToResult (h 2) (12 200))
                """
            )
        )
    }

    /** A repeated `import!` contributes no second copy of the same owner (f1 :123). */
    @Test
    fun `re-importing a module does not duplicate its answer`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "UnionRepeat",
                $$"""
                    !(import! &self hmodone)
                    !(import! &self hmodtwo)
                    !(import! &self hmodone)
                    !(assertEqualToResult (h 2) (12 200))
                """
            )
        )
    }

    /** TARGET: an import into a NAMED space contributes no owner to a `&self` call. */
    @Test
    fun `an import into a named space adds no owner`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "UnionNamed",
                $$"""
                    !(import! &self hmodone)
                    !(import! &m hmodtwo)
                    !(assertEqual (h 2) 12)
                """
            )
        )
    }

    /** Declared types work the same way — the f1 `(-> Number Number)` shape. */
    @Test
    fun `declared-type owners union too`(@TempDir tmp: Path) {
        File(tmp.toFile(), "hmodone.metta").writeText(
            "(: h (-> Number Number))\n(= (h ${'$'}x) (+ ${'$'}x 10))\n"
        )
        File(tmp.toFile(), "hmodtwo.metta").writeText(
            "(: h (-> Number Number))\n(= (h ${'$'}x) (* ${'$'}x 100))\n"
        )
        File(tmp.toFile(), "UnionTyped.metta").writeText(
            $$"""
                !(import! &self hmodone)
                !(import! &self hmodtwo)
                !(assertEqualToResult (h 2) (12 200))
            """.trimIndent() + "\n"
        )
        val summary = JettaTestRunner().run(tmp.toFile(), emptyMap())
        assertPasses(summary.entries.single { it.file.startsWith("UnionTyped") })
    }
}
