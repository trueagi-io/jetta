package net.singularity.jetta.test

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * The grounded primitives the reference stdlib is built ON.
 *
 * hyperon loads `lib/src/metta/runner/stdlib/stdlib.metta` as MeTTa source (`include_str!` as
 * `METTA_CODE`), and 38 of the stdlib entries JeTTa lacks are DEFINED IN MeTTa in that file — they
 * come from compiling it, not from being written by hand. But they stand on primitives grounded in
 * Rust, and those we do have to implement. `car-atom` and `cdr-atom` are the clearest example:
 * that file defines both in terms of `chain`, `decons-atom` and `unify`.
 *
 * `chain` is rewritten onto `let` rather than given its own runtime, so it reuses the whole tested
 * binding path. DIVERGENCE, deliberate: hyperon's `chain` takes ONE reduction step where `let`
 * evaluates fully. The answers agree whenever the bound expression reaches a value in one step —
 * every use in `stdlib.metta` and in the corpus — and differ for a program that relies on stepwise
 * control, which is what `chain` exists for in minimal MeTTa.
 */
class MinimalMettaPrimitivesTest {

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

    /** Both assertions are the corpus's `chain.metta` verbatim. */
    @Test
    fun `chain binds the value of its first argument`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "ChainBind",
                $$"""
                    !(assertEqual (chain (+ 2 4) $n (* 3 $n)) 18)
                    !(assertEqual (chain (+ 1 3) $n (chain (* 2 $n) $m (+ $n $m))) 12)
                """
            )
        )
    }

    /**
     * `decons-atom` yields the TWO-element `(head (tail…))` shape — `(decons-atom (Cons X Nil))`
     * is `(Cons (X Nil))`, not the flat expression. That is what makes it the exact inverse of
     * `cons-atom` and what lets a destructuring `unify` take the result apart.
     */
    @Test
    fun `decons-atom splits into head and tail`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "DeconsAtom",
                $$"""
                    !(assertEqual (decons-atom (Cons X Nil)) (Cons (X Nil)))
                    !(assertEqual (decons-atom (1 2 3)) (1 (2 3)))
                    !(assertEqual (decons-atom (a)) (a ()))
                """
            )
        )
    }

    @Test
    fun `cons-atom prepends to an expression`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "ConsAtom",
                $$"""
                    !(assertEqual (cons-atom a (b c)) (a b c))
                    !(assertEqual (cons-atom a ()) (a))
                """
            )
        )
    }

    /** The composition the reference stdlib actually uses to define `car-atom` / `cdr-atom`. */
    @Test
    fun `chain over decons-atom is how the stdlib takes a list apart`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "ChainDecons",
                $$"""
                    !(assertEqual (chain (decons-atom (1 2 3)) $ht (car-atom $ht)) 1)
                    !(assertEqual (chain (decons-atom (1 2 3)) $ht (cdr-atom $ht)) ((2 3)))
                """
            )
        )
    }
}
