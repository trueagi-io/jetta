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

    // --- running a minimal-MeTTa program end to end ---------------------------------------

    /**
     * `he_minimalmetta.metta` verbatim: integer division written in minimal MeTTa as a
     * CPS recursion over `chain`/`unify`/`eval`. Three separate defects stood between this and
     * an answer, and each is pinned by a narrower test below.
     *
     * It also needs the deep stack: 350000 / 5 is 70000 recursive steps, and a default JVM
     * stack overflows at around a thousand (see `DeepStack`).
     */
    @Test
    fun `a minimal-MeTTa CPS program runs to an answer`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "MinimalDiv",
                $$"""
                    (= (div $x $y $accum)
                       (chain (eval (- $x $y)) $r1
                         (chain (eval (< $r1 0)) $r2
                           (chain (unify $r2 True
                             $accum
                             (chain (eval (+ 1 $accum)) $inc
                               (chain (eval (div $r1 $y $inc)) $r4 $r4)
                             )) $r3 $r3
                           )
                         )
                       )
                    )
                    !(assertEqual (chain (eval (div 350000 5 0)) $rr $rr) 70000)
                """
            )
        )
    }

    /**
     * A rule may reuse the NAME of a grounded operator at a different arity. `div` is the
     * grounded two-argument division, and the program above defines `div/3` on top of it —
     * which hyperon allows. JeTTa turned any `div` head into the operator `Special`, so the
     * three-argument form was "a misapplied special" = data, and it was also marked
     * shadowed-by-builtin so no method was emitted: the call silently never reduced.
     */
    @Test
    fun `a rule may reuse an operator name at a different arity`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "OperatorNameArity",
                $$"""
                    (= (div $x $y $accum) (+ $accum (- $x $y)))
                    !(assertEqual (div 10 5 1) 6)
                    !(assertEqual (div 10 5) 2)
                """
            )
        )
    }

    /**
     * A multivalued call LIFTED out of its parent kept its lambda arguments un-rewritten, so a
     * multivalued call inside one of those branches never got its own `map?`/`flat-map?` wrap
     * and the branch lambda — parameter typed `Atom` — was applied straight to a `List`
     * (ClassCastException).
     *
     * The shape is exactly minimal MeTTa's: a `unify` in the VALUE position of a `chain`, with
     * another `chain` inside the unify's else-branch. Written the other way round — the `unify`
     * in the chain's BODY — it always worked, which is the second assertion.
     */
    @Test
    fun `a lifted call has its branch lambdas rewritten`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "LiftedBranches",
                $$"""
                    (= (f $x) (chain (unify $x True 1 (chain (eval (+ 1 2)) $i $i)) $r $r))
                    (= (g $x) (chain (eval (< $x 1)) $d (unify $d True 1 (chain (eval (+ 1 2)) $i $i))))
                    !(assertEqual (f False) 3)
                    !(assertEqual (g 5) 3)
                """
            )
        )
    }

    /**
     * Recursion deeper than a default JVM stack. Minimal MeTTa recurses once per iteration, so
     * depth is the program's step count, and the reference runs it on its own heap-allocated
     * stack. 20000 steps overflows a default stack several times over.
     */
    @Test
    fun `recursion deeper than a default stack completes`(@TempDir tmp: Path) {
        assertPasses(
            runOne(
                tmp.toFile(), "DeepRecursion",
                $$"""
                    (= (count $n $acc)
                       (chain (eval (< $n 1)) $done
                         (chain (unify $done True
                                  $acc
                                  (chain (eval (- $n 1)) $n1
                                    (chain (eval (+ 1 $acc)) $a1
                                      (chain (eval (count $n1 $a1)) $r $r))))
                                $x $x)))
                    !(assertEqual (chain (eval (count 20000 0)) $rr $rr) 20000)
                """
            )
        )
    }
}
