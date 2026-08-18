package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Form-2 pattern-`let` — `(let ($head $tail) VALUE BODY)` — with MORE THAN ONE pattern variable.
 *
 * The lowering onto the `letMatch` runtime helper used to be capped at a single pattern variable,
 * so `($head $tail)` — the shape every `decons-atom` consumer in the reference `stdlib.metta`
 * writes, and the whole point of `decons-atom` — fell through to the generic rewrite as INERT
 * data. That failure was silent in the worst way: the BODY was still evaluated, with the pattern
 * variables free, so a `(unify $head -> True False)` inside it "succeeded" against an unbound
 * variable, and the `let` itself survived into the result as a term. The reference `is-function`
 * answered `[Empty]`; nothing reported an error.
 *
 * Each program asserts with `!(assertEqual …)`, which throws from `__main` on a wrong answer, so a
 * green `invoke` IS the assertion. Every case here was first checked to FAIL against a wrong
 * expectation — `letMatch` contributes nothing for a result that does not unify, and an empty bag
 * silently satisfies nothing.
 */
class PatternLetTest : GeneratorTestBase() {

    private fun run(name: String, code: String) {
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach(::println)
                assertTrue(messageCollector.list().isEmpty(), "no diagnostics expected")
                val classes = result.toMap().toClasses()
                JettaProgram.init(name)
                classes[name]!!.getMethod("__main").invoke(null)
            }
    }

    private fun String.d() = replace('_', '$')

    /** Two pattern variables, bound positionally in document order. */
    @Test
    fun `a two-variable pattern binds both variables`() {
        run(
            "PatLetPair",
            """
            (= (fst _p) (let (_a _b) _p _a))
            (= (snd _p) (let (_a _b) _p _b))
            !(assertEqual (fst (X Y)) X)
            !(assertEqual (snd (X Y)) Y)
            """.trimIndent().d()
        )
    }

    /** The reference stdlib's shape: destructure what `decons-atom` returns. */
    @Test
    fun `the decons-atom head-tail shape destructures`() {
        run(
            "PatLetDecons",
            """
            (= (my-head _x) (let (_h _t) (decons-atom _x) _h))
            (= (my-tail _x) (let (_h _t) (decons-atom _x) _t))
            !(assertEqual (my-head (1 2 3)) 1)
            !(assertEqual (my-tail (1 2 3)) (2 3))
            """.trimIndent().d()
        )
    }

    /** Three variables, to pin that the arity is not special-cased at two. */
    @Test
    fun `a three-variable pattern binds all three`() {
        run(
            "PatLetTriple",
            """
            (= (mid _p) (let (_a _b _c) _p _b))
            !(assertEqual (mid (P Q R)) Q)
            """.trimIndent().d()
        )
    }

    /**
     * A non-variable position in the pattern still has to MATCH, not just bind around it — and a
     * value that fails to unify contributes NO result (the empty bag, as in `match`), which is a
     * different thing from the empty expression `()`.
     */
    @Test
    fun `a literal in the pattern must match and contributes no binding`() {
        run(
            "PatLetLiteral",
            """
            (= (pick _p) (let (tag _a _b) _p _a))
            !(assertEqual (pick (tag X Y)) X)
            !(assertEqualToResult (pick (other X Y)) ())
            """.trimIndent().d()
        )
    }

    /** Nested inside a `unify` branch — how the reference stdlib actually reaches it. */
    @Test
    fun `a pattern let inside a unify branch is lowered`() {
        run(
            "PatLetInUnify",
            """
            (= (k _x) (unify _x A (let (_a _b) (X Y) _a) no))
            !(assertEqual (k A) X)
            !(assertEqual (k B) no)
            """.trimIndent().d()
        )
    }

    /**
     * The reference `is-function`'s body, end to end: `get-metatype`, a `unify` on the metatype,
     * `decons-atom`, and the two-variable pattern `let` that reads the arrow head back out. This
     * is the stdlib probe that the single-variable cap held at `[Empty]`.
     */
    @Test
    fun `the reference is-function body answers over an arrow type`() {
        run(
            "PatLetIsFunction",
            """
            (: my-isf (-> Type Bool))
            (= (my-isf _type)
                (let _mtype (get-metatype _type)
                    (unify _mtype Expression
                        (let (_h _t) (decons-atom _type)
                            (unify _h -> True False))
                    False)))
            !(assertEqual (my-isf (-> A B)) True)
            !(assertEqual (my-isf A) False)
            !(assertEqual (my-isf (Foo Bar)) False)
            """.trimIndent().d()
        )
    }

    /**
     * A LITERAL-headed expression is a data tuple, not an application. Reachable as a pattern-let
     * value (`(let (_a _b 3) (1 2 3) …)`, corpus `letlet.metta`), where it used to crash type
     * inference outright: `NotImplementedError: An operation is not implemented: atom=1`.
     */
    @Test
    fun `a literal-headed data tuple compiles as the pattern let value`() {
        run(
            "PatLetLiteralHead",
            """
            (= (g) (let (_a _b 3) (1 2 3) (_a _b)))
            !(assertEqual (g) (1 2))
            """.trimIndent().d()
        )
    }
}
