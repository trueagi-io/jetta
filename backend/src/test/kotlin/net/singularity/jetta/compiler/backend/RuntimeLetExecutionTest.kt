package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `let` CONSTRUCTED at run time by a rule body, which no rewriter ever saw.
 *
 * A source-level `let` is lowered at compile time onto a lambda application or `letMatch`. But the
 * reference defines `lambda` as a rule whose body BUILDS one — `(= ((lambda $var $body) $arg) (let
 * $var $arg $body))` — so `((lambda $x (+ $x 1)) 2)` rewrites to the term `(let $x 2 (+ $x 1))`,
 * and the reducer has to run it. It used to be returned inert, `let` and all.
 *
 * A green `invoke` IS the assertion. `~` stands for `$` (see `MinimalFoldTest`).
 */
class RuntimeLetExecutionTest : GeneratorTestBase() {

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

    private val lambda = """
        (: lambda (-> Atom ~t (-> ~a ~t)))
        (= ((lambda ~var ~body) ~arg) (let ~var ~arg ~body))
    """.trimIndent()

    /** The reference `lambda`: a rule body builds the `let`, and the reducer runs it. */
    @Test
    fun `a let built by a rule body is executed`() {
        run(
            "RuntimeLet",
            """
            $lambda
            !(assertEqual ((lambda ~x (+ ~x 1)) 2) 3)
            """.trimIndent().v()
        )
    }

    /** A pattern binding, not just a single variable. */
    @Test
    fun `a runtime let destructures a tuple pattern`() {
        run(
            "RuntimeLetPattern",
            """
            $lambda
            !(assertEqual ((lambda (~x ~y) (+ ~x ~y)) (2 7)) 9)
            """.trimIndent().v()
        )
    }

    /** Curried through another rule — the `part-appl` / `inc` shape. */
    @Test
    fun `a runtime let reached through a partial application is executed`() {
        run(
            "RuntimeLetPartial",
            """
            $lambda
            (= (part-appl ~f ~x) (lambda ~y (~f ~x ~y)))
            (= (inc) (part-appl + 1))
            !(assertEqual ((inc) 5) 6)
            """.trimIndent().v()
        )
    }

    /** A pattern the value cannot unify with contributes NO result, as `letMatch` does. */
    @Test
    fun `a runtime let whose pattern does not unify yields nothing`() {
        run(
            "RuntimeLetNoMatch",
            """
            (= (bind-pair ~v) (let (tag ~x) ~v ~x))
            !(assertEqual (bind-pair (tag A)) A)
            !(assertEqualToResult (bind-pair (other A)) ())
            """.trimIndent().v()
        )
    }
}
