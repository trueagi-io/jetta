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
            (: walk (-> Expression Atom))
            (= (walk $e) (if (== $e ()) done (let $t (cdr-atom $e) (walk $t))))
            !(assertEqual (walk (1 2 3)) done)
        """.trimIndent()
    )
}
