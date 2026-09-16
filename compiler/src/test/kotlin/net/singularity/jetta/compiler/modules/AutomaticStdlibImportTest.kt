package net.singularity.jetta.compiler.modules

import net.singularity.jetta.compiler.Compiler
import net.singularity.jetta.compiler.logger.LogLevel
import net.singularity.jetta.runtime.space.ManifestExtension
import net.singularity.jetta.runtime.space.ManifestSerializer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reference interpreter loads its standard library for every program it runs, so a program
 * that calls `if-error` or queries a library declaration expects it there without saying so.
 * `autoImportStdlib` does the same by prepending `(import! &self stdlib)`, linking the library
 * shipped inside the compiler's jar.
 *
 * On by default (`--no-stdlib` opts out), so these tests pass the flag explicitly either way
 * rather than relying on it.
 */
class AutomaticStdlibImportTest {

    @Test
    fun `an automatic import links the shipped library and lists it for the runtime`(
        @TempDir src: Path,
        @TempDir out: Path,
    ) {
        // No import! written anywhere, and the program uses a MeTTa-level library function.
        File(src.toFile(), "auto.metta").writeText(
            """
            !(assertEqual (if-error (Error foo Bar) caught fine) caught)
            !(println! (map-atom (1 2 3) ${'$'}x (+ ${'$'}x 10)))
            """.trimIndent()
        )
        compile(File(src.toFile(), "auto.metta").absolutePath, out, autoImport = true)

        // Linked, not rebuilt: the library's class is not among the program's artifacts.
        assertTrue(!Files.isRegularFile(out.resolve("stdlib.class")), "the library is linked, not recompiled")

        val manifest = ManifestSerializer.load(out.resolve("auto.manifest.json"))
        val ext = manifest.extension as ManifestExtension.DeepCopy
        assertEquals(listOf("stdlib"), ext.loadModules.map { it.spaceId })

        val output = run(out, "auto")
        assertTrue(output.contains("(11 12 13)"), "expected the library's map-atom to answer, got:\n$output")
    }

    /** The library's ATOMS reach `&self` too, so a reflective query over its declarations works. */
    @Test
    fun `the library's declarations are visible to a reflective match`(
        @TempDir src: Path,
        @TempDir out: Path,
    ) {
        File(src.toFile(), "refl.metta").writeText(
            """
            !(println! (match &self (: id ${'$'}t) found-id))
            """.trimIndent()
        )
        compile(File(src.toFile(), "refl.metta").absolutePath, out, autoImport = true)
        val output = run(out, "refl")
        assertTrue(output.contains("found-id"), "expected the library declaration to be matchable, got:\n$output")
    }

    /** Off, nothing changes: no module is imported and no library atom is anywhere near. */
    @Test
    fun `without the flag a program gets no library at all`(
        @TempDir src: Path,
        @TempDir out: Path,
    ) {
        File(src.toFile(), "bare.metta").writeText(
            """
            !(println! (match &self (: id ${'$'}t) found-id))
            """.trimIndent()
        )
        compile(File(src.toFile(), "bare.metta").absolutePath, out, autoImport = false)
        val manifest = ManifestSerializer.load(out.resolve("bare.manifest.json"))
        assertEquals(emptyList(), (manifest.extension as ManifestExtension.DeepCopy).loadModules)
        assertTrue(!run(out, "bare").contains("found-id"), "no library declaration should be matchable")
    }

    /** A program that imports the library itself keeps its own import, at its own position. */
    @Test
    fun `an explicit import is not duplicated`(@TempDir src: Path, @TempDir out: Path) {
        File(src.toFile(), "explicit.metta").writeText(
            """
            !(import! &self stdlib)
            !(println! (if-error (Error foo Bar) caught fine))
            """.trimIndent()
        )
        compile(File(src.toFile(), "explicit.metta").absolutePath, out, autoImport = true)
        val manifest = ManifestSerializer.load(out.resolve("explicit.manifest.json"))
        assertEquals(listOf("stdlib"), (manifest.extension as ManifestExtension.DeepCopy).loadModules.map { it.spaceId })
        assertTrue(run(out, "explicit").contains("caught"))
    }

    /**
     * With the library in the space, a reduction can reach its `(= (empty) Empty)` rule — and
     * `Empty` is the reference's "no result" marker, not a value. A reasoning rule whose guard
     * falls through to `(empty)` must still answer `()`, as the reference does (probed); before
     * `JettaCallSite` absorbed the symbol, this answered `(Empty Empty)`.
     */
    @Test
    fun `a reduction reaching the library's Empty contributes no result`(
        @TempDir src: Path,
        @TempDir out: Path,
    ) {
        File(src.toFile(), "reason.metta").writeText(
            """
            (: Entity Type)
            (: Plato Entity)
            (: Human (-> Entity Type))
            (: Mortal (-> Entity Type))
            (: HumansAreMortal (-> (Human ${'$'}t) (Mortal ${'$'}t)))
            (: PlatoIsHuman (Human Plato))
            (: T Type)
            (= (= ${'$'}x ${'$'}x) T)
            (= (= ${'$'}type T)
               (match &self (: ${'$'}impl (-> ${'$'}cause ${'$'}type))
                  (if (== ${'$'}cause ${'$'}type) (empty) (= ${'$'}cause T))))
            (: Sam Entity)
            !(assertEqualToResult (= (Human Sam) T) ())
            """.trimIndent()
        )
        compile(File(src.toFile(), "reason.metta").absolutePath, out, autoImport = true)
        run(out, "reason") // the assert throws inside the program if the bag is not empty
    }

    /**
     * `assertAlphaEqual*` exists only in the library — we ground no twin of it — and its MeTTa
     * definition passes its OWN self-application as the third argument of
     * `_assert-results-are-alpha-equal`, for the error message. With that helper missing the call
     * was an unresolved head, so the argument was reduced, the assert re-entered itself and
     * `d5_auto_types` overflowed a 256MB stack. This is that file's assert.
     */
    @Test
    fun `the library's alpha assert compares up to a renaming of variables`(
        @TempDir src: Path,
        @TempDir out: Path,
    ) {
        File(src.toFile(), "alpha.metta").writeText(
            """
            !(pragma! type-check auto)
            (: Entity Type)
            (: Socrates Entity)
            (: Human (-> Entity Type))
            (: Mortal (-> Entity Type))
            (: HumansAreMortal (-> (Human ${'$'}t) (Mortal ${'$'}t)))
            !(assertAlphaEqualToResult
               (HumansAreMortal (Human Socrates))
               ((Error (HumansAreMortal (Human Socrates)) (BadArgType 1 (Human ${'$'}t) Type))))
            !(assertAlphaEqual (Father ${'$'}X) (Father ${'$'}Y))
            """.trimIndent()
        )
        compile(File(src.toFile(), "alpha.metta").absolutePath, out, autoImport = true)
        run(out, "alpha") // each assert throws inside the program if it does not hold
    }

    /**
     * And the same assert FAILS when it should. Worth its own test: while the library was not
     * linked, `assertAlphaEqualToResult` was an unresolved head, i.e. inert data, so a wrong
     * assert succeeded silently — `d5_auto_types` passed with one of its seven asserts never
     * running, which is how the defect above stayed hidden.
     */
    @Test
    fun `a wrong alpha assert is not silent`(@TempDir src: Path, @TempDir out: Path) {
        File(src.toFile(), "wrong.metta").writeText(
            """
            !(assertAlphaEqualToResult (+ 1 2) (WILDLY WRONG))
            """.trimIndent()
        )
        compile(File(src.toFile(), "wrong.metta").absolutePath, out, autoImport = true)
        val output = runExpectingFailure(out, "wrong")
        assertTrue(
            output.contains("_assert-results-are-alpha-equal failed"),
            "expected the assert to fail loudly, got:\n" + output,
        )
    }

    /**
     * `(get-atoms &self)` answers the program's OWN atoms with the library linked — measured
     * against the reference, which keeps the library in a space of its own and delegates queries
     * into it rather than copying. Ours copies, so the copies are marked as not the program's.
     * `f1_imports` opens by asserting this, at a point where the program has no facts at all.
     */
    @Test
    fun `get-atoms answers the program's own atoms, not the library's`(
        @TempDir src: Path,
        @TempDir out: Path,
    ) {
        File(src.toFile(), "own.metta").writeText(
            """
            !(assertEqualToResult ((let ${'$'}x (get-atoms &self) (get-type ${'$'}x))) ())
            (MyFact apple)
            !(println! (collapse (get-atoms &self)))
            """.trimIndent()
        )
        compile(File(src.toFile(), "own.metta").absolutePath, out, autoImport = true)
        val output = run(out, "own")
        assertTrue(
            output.contains("((MyFact apple))"),
            "expected the program's own atom alone, got:\n" + output,
        )
    }

    /**
     * A call that uses a LIBRARY name at an arity the library does not declare is data, and the
     * multivalued lift must not treat it as a call.
     *
     * The library's `map-atom` is `(-> Expression Variable Atom Expression)` and multivalued;
     * `holfunctions.metta` writes the two-argument `(map-atom (1 2 3) mapfun)`, which
     * `Context.resolveAtom`'s arity guard correctly leaves inert. The lift looked the head up by
     * NAME, found the multivalued three-argument entry and wrapped the inert term in a `map?`,
     * so `simpleMap` was handed an `Expression` where it wanted a `List` — and in the file's own
     * shape, a method whose descriptor promised an `Expression` returning a `List`, which is a
     * VerifyError at class load. The program must RUN; what it then answers is the inert term,
     * since neither we nor the reference define `map-atom` at this arity.
     */
    @Test
    fun `a library name used at another arity is data, not a multivalued call`(
        @TempDir src: Path,
        @TempDir out: Path,
    ) {
        File(src.toFile(), "arity.metta").writeText(
            """
            (= (mapfun ${'$'}x) (+ ${'$'}x 1))
            (= (f2b) (map-atom (1 2 3) mapfun))
            !(println! (f2b))
            """.trimIndent()
        )
        compile(File(src.toFile(), "arity.metta").absolutePath, out, autoImport = true)
        val output = run(out, "arity")
        assertTrue(output.contains("map-atom"), "expected the inert term, got:\n" + output)
    }

    private fun compile(file: String, out: Path, autoImport: Boolean) {
        val compiler = Compiler(
            files = listOf(file),
            outputDir = out.toAbsolutePath().toString(),
            logLevel = LogLevel.ERROR,
            autoImportStdlib = autoImport,
        )
        assertEquals(0, compiler.compile(), "compiler returned non-zero for $file")
    }

    /**
     * Run the compiled program in a fresh JVM. The test classpath carries the library's shipped
     * artifacts as resources, which is how the running program finds its space.
     */
    private fun run(out: Path, programName: String): String {
        val classpath = "${out.toAbsolutePath()}${File.pathSeparator}${System.getProperty("java.class.path")}"
        val proc = ProcessBuilder("java", "-Djetta.dataDir=${out.toAbsolutePath()}", "-cp", classpath, programName)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), "java exited non-zero. output:\n$output")
        return output
    }

    /** [run] for a program expected to fail — a failing assert exits non-zero. */
    private fun runExpectingFailure(out: Path, programName: String): String {
        val classpath = "${out.toAbsolutePath()}${File.pathSeparator}${System.getProperty("java.class.path")}"
        val proc = ProcessBuilder("java", "-Djetta.dataDir=${out.toAbsolutePath()}", "-cp", classpath, programName)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor() != 0, "expected a non-zero exit. output:\n$output")
        return output
    }
}
