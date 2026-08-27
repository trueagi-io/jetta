package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An application whose HEAD is itself an application — `((h …) a …)` — where the head has to be
 * reduced before the whole term is a redex.
 *
 * `curry`/`lambda` are defined by rules with an Expression-headed left side, which
 * `FunctionRewriter` cannot lower to a compiled function, so they live as space `=` facts and the
 * application has to be reduced at run time. Two steps were missing:
 *
 *  * codegen evaluated a RESOLVED head only to store its value in a data tuple, so
 *    `((is-socrates) Human)` — head `(is-socrates)`, a compiled zero-arg function whose body is
 *    `(curry-a is Socrates)` — never reached the reducer at all.
 *  * the reducer never normalised a head that is itself a redex, so even reaching it, the curry
 *    rule could not see the application until the head had become `(curry-a is Socrates)`.
 *
 * A green `invoke` IS the assertion. `~` stands for `$` (see `MinimalFoldTest`).
 */
class ExpressionHeadReductionTest : GeneratorTestBase() {

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

    private fun String.v() = replace('~', '$')

    private val curry = """
        (: curry-a (-> (-> ~a ~b ~c) ~a (-> ~b ~c)))
        (= ((curry-a ~f ~a) ~b) (~f ~a ~b))
    """.trimIndent()

    /** The head written out directly — this already worked, and must keep working. */
    @Test
    fun `a curried application written directly reduces`() {
        run(
            "CurryDirect",
            """
            $curry
            !(assertEqual ((curry-a + 2) 3) 5)
            """.trimIndent().v()
        )
    }

    /**
     * The same application reached through a zero-arg function: the head must be REDUCED, and its
     * value — not the call — becomes the head of the term the curry rule then matches.
     */
    @Test
    fun `a curried application reached through a compiled head reduces`() {
        run(
            "CurryThroughHead",
            """
            $curry
            (= (add-two) (curry-a + 2))
            !(assertEqual ((add-two) 3) 5)
            """.trimIndent().v()
        )
    }

    /** The same, over a user function rather than a grounded operator (d2's `is-socrates`). */
    @Test
    fun `a curried application over a user function reduces through its head`() {
        run(
            "CurryUserFn",
            """
            $curry
            (: Socrates Entity)
            (: Human Entity)
            (: is (-> Entity Entity Bool))
            (= (is Socrates Human) True)
            (= (is-socrates) (curry-a is Socrates))
            !(assertEqual ((curry-a is Socrates) Human) True)
            !(assertEqual ((is-socrates) Human) True)
            """.trimIndent().v()
        )
    }

    /** A PARTIAL application still has no rule, so it stays inert — MeTTa requires that. */
    @Test
    fun `a partial application stays inert`() {
        run(
            "CurryPartial",
            """
            (: curry (-> (-> ~a ~b ~c) (-> ~a (-> ~b ~c))))
            (= (((curry ~f) ~x) ~y) (~f ~x ~y))
            !(assertEqualToResult ((curry +) 2) (((curry +) 2)))
            !(assertEqual (((curry +) 2) 3) 5)
            """.trimIndent().v()
        )
    }

    /**
     * The opposite shape, which the same codegen branch decides: a DATA tuple whose head and
     * elements are side-effecting calls. Evaluating the head is what makes its effect happen —
     * quoting it dropped the first write and the tuple came out `(2)`.
     */
    @Test
    fun `a tuple headed by a side-effecting call runs every element`() {
        run(
            "TupleHeadEffects",
            """
            (= (seetuple ~x) 42)
            !(seetuple ((add-atom &self (m 1)) (add-atom &self (m 2))))
            !(assertEqual (msort (collapse (match &self (m ~y) ~y))) (1 2))
            """.trimIndent().v()
        )
    }
}
