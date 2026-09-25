package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Behaviours pinned by `tests/hyperon` (each expected answer measured on metta-repl 0.2.10), in the
 * shape of a unit test so a regression is caught by `./gradlew test` too.
 *
 * Each program asserts via `!(assert…)`; a wrong answer throws AssertionError from `__main`, so a
 * green `invoke` IS the assertion.
 */
class ReferenceSemanticsTest : GeneratorTestBase() {

    private fun run(name: String, code: String) {
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach(::println)
                assertTrue(messageCollector.list().isEmpty())
                val classes = result.toMap().toClasses()
                JettaProgram.init(name)
                classes[name]!!.getMethod("__main").invoke(null)
            }
    }

    /** h09: an operator passed as data inside a body crashed type inference (`NotImplementedError`). */
    @Test
    fun `an operator passed as data in a body is the symbol`() = run(
        "OperatorAsBodyData",
        $$"""
            (= (mk) (cons-atom + (1 2)))
            !(assertEqualToResult (mk) ((+ 1 2)))
        """.trimIndent()
    )

    /**
     * h08: a `let` template is reduced after substitution, so a term built by a grounded operation
     * runs there — but not under `function`/`chain`, and not where a callee takes the term.
     */
    @Test
    fun `a let template reduces a built term where it needs the value`() = run(
        "LetReducesTemplate",
        $$"""
            !(assertEqualToResult (let $x (cons-atom + (1 2)) $x) (3))
            !(assertEqualToResult (let $x (cons-atom + (1 2)) (foo $x)) ((foo 3)))
            (: q (-> Atom Atom))
            (= (q $x) $x)
            !(assertEqualToResult (let $y (q (+ 1 2)) $y) (3))
            (= (useit) (let $x (cons-atom + (1 2)) $x))
            !(assertEqualToResult (useit) (3))
            !(assertEqualToResult (function (chain (cons-atom + (1 2)) $x (return $x))) ((+ 1 2)))
            (: walk (-> Expression %Undefined%))
            (= (walk $e) (if (== $e ()) done (let $t (cdr-atom $e) (walk $t))))
            !(assertEqual (walk (1 2 3)) done)
        """.trimIndent()
    )

    /** h05: a held non-deterministic term answers ALL its results, each use independently. */
    @Test
    fun `a held non-deterministic term answers all its results`() = run(
        "HeldNondet",
        $$"""
            (: inc (-> Expression Number))
            (= (inc $x) (+ $x 1))
            !(assertEqualToResult (inc (superpose (1 2))) (2 3))
            (: tw (-> Expression Number))
            (= (tw $x) (+ $x $x))
            !(assertEqualToResult (tw (superpose (1 2))) (2 3 3 4))
            (: pick2 (-> Expression Expression))
            (= (pick2 $cases) (superpose ($cases $cases)))
            !(assertEqualToResult (pick2 (A B)) ((A B) (A B)))
        """.trimIndent()
    )

    /** h11: a head defined in the file answers what the space holds after an add or a remove. */
    @Test
    fun `rules added or removed at run time change a head the file defines`() = run(
        "CompiledHeadRules",
        $$"""
            (= (color) red)
            !(add-atom &self (= (color) blue))
            !(assertEqualToResult (collapse (color)) ((red blue)))
            (= (function1) OK)
            !(remove-atom &self (= (function1) OK))
            !(assertEqualToResult (function1) ((function1)))
            !(add-atom &self (= (function1) (OK)))
            !(assertEqualToResult (function1) ((OK)))
        """.trimIndent()
    )

    /**
     * h02: a function declaring its result `Atom` answers its body substituted, not reduced — the
     * parameters still evaluated or held by their own types. (A body in minimal MeTTa is still
     * evaluated past its `return` — h13, a known divergence.)
     */
    @Test
    fun `an Atom-result function answers its body as a term`() = run(
        "AtomResultBody",
        $$"""
            (: q3 (-> Atom Atom))
            (= (q3 $x) (foo (+ 1 1) $x))
            !(assertEqualToResult (q3 (+ 1 2)) ((foo (+ 1 1) (+ 1 2))))
            (= (dbl $x) (* $x 2))
            (: mkq (-> Atom))
            (= (mkq) (dbl 4))
            !(assertEqualToResult (mkq) ((dbl 4)))
            (: q4 (-> Atom))
            (= (q4) (if True (+ 1 1) 0))
            !(assertEqualToResult (q4) ((if True (+ 1 1) 0)))
            (: q6 (-> Number Atom))
            (= (q6 $x) (+ $x 1))
            !(assertEqualToResult (q6 (+ 1 1)) ((+ 2 1)))
            (: q7 (-> Atom Atom))
            (= (q7 $x) $x)
            !(assertEqualToResult (q7 (+ 1 2)) ((+ 1 2)))
        """.trimIndent()
    )
}
