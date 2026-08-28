package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `match` template whose nested CALLS are written over the match's own pattern variables.
 *
 * The lambda path of [MatchTemplateReductionTest] cannot express this one. A pattern variable is
 * CAPTURED into the template lambda when that lambda is created, which is before the match has
 * run — so a call written over it is compiled with an unbound `Variable` argument, and a query
 * inside it matches everything in the space. Whatever the template does NOT evaluate is quoted as
 * data, and the bindings are substituted into that data only at the very end, which is how c3's
 *
 *   (match &self (.tv (Implication $y $x) (stv $s $c))
 *     (stv (* $s (s-tv (TV $y))) (* $c (c-tv (TV $y)))))
 *
 * came out as `(stv (* 0.8 (s-tv <every .tv fact in the space>)) …)`: the multiplication frozen as
 * a term, its operand the whole store. Four calls over three pattern variables — more than any
 * single lift can drive.
 *
 * Such a template is substituted first and reduced after, by `matchReduceTemplate`: one applicative
 * walk per match result, arguments before heads.
 *
 * Each test invokes `__main`; a failed `!(assertEqual …)` throws.
 */
class MatchTemplateCallReductionTest : GeneratorTestBase() {

    private fun run(name: String, code: String) {
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, mc) ->
                mc.list().forEach(::println)
                assertTrue(mc.list().isEmpty(), mc.list().toString())
                val cls = result.toMap().toClasses()[name]!!
                JettaProgram.init(name)
                cls.getMethod("__main").invoke(null)
            }
    }

    /** TWO pattern variables: one feeds a call, the other is data in the same template. */
    @Test
    fun `a template call over one pattern variable runs beside another as data`() {
        run(
            "TemplateCallTwoVars",
            $$"""
                (= (TV $x) (match &self (.tv $x $stv) $stv))
                (= (con $x) (match &self (.imp $y $x $s) (r $s (TV $y))))
                (.tv A (v 5))
                (.imp A B 9)
                !(assertEqual (con B) (r 9 (v 5)))
            """.trimIndent()
        )
    }

    /** The call is the only thing in the template, and its argument is the pattern variable. */
    @Test
    fun `a template call over a pattern variable is evaluated, not left as a term`() {
        run(
            "TemplateCallOneVar",
            $$"""
                (= (TV $x) (match &self (.tv $x $stv) $stv))
                (= (con $x) (match &self (.imp $y $x) (r (TV $y))))
                (.tv A (v 5))
                (.imp A B)
                !(assertEqual (con B) (r (v 5)))
            """.trimIndent()
        )
    }

    /**
     * c3's Implication rule, in miniature: arithmetic over a pattern variable AND over the result
     * of a call over another one. Both halves have to arrive as values for the `*` to compute.
     */
    @Test
    fun `arithmetic over a pattern variable and a call result computes`() {
        run(
            "TemplateArithmetic",
            $$"""
                (= (s-tv (stv $s $c)) $s)
                (= (TV $x) (match &self (.tv $x $stv) $stv))
                (= (imp-tv $x)
                   (match &self (.tv (Implication $y $x) (stv $s $c))
                     (stv (* $s (s-tv (TV $y))) $c)))
                (.tv P (stv 0.5 0.8))
                (.tv (Implication P Q) (stv 0.8 1.0))
                !(assertEqual (imp-tv Q) (stv 0.4 1.0))
            """.trimIndent()
        )
    }

    /** A call in the template that yields SEVERAL results contributes all of them. */
    @Test
    fun `a forking template call contributes every result`() {
        run(
            "TemplateCallForks",
            $$"""
                (= (opts $x) (match &self (.opt $x $o) $o))
                (= (con $x) (match &self (.imp $y $x) (r (opts $y))))
                (.opt A one)
                (.opt A two)
                (.imp A B)
                !(assertEqualToResult (con B) ((r one) (r two)))
            """.trimIndent()
        )
    }

    /**
     * The template's own DATA is untouched by the walk: a data constructor has neither an `=` rule
     * nor a registry entry, so it stays exactly as written — including a nested one that happens to
     * share a head name with nothing at all.
     */
    @Test
    fun `data around the call is returned verbatim`() {
        run(
            "TemplateDataVerbatim",
            $$"""
                (= (TV $x) (match &self (.tv $x $stv) $stv))
                (= (con $x) (match &self (.imp $y $x) (Wrap (Tag $y) (TV $y) done)))
                (.tv A (v 5))
                (.imp A B)
                !(assertEqual (con B) (Wrap (Tag A) (v 5) done))
            """.trimIndent()
        )
    }
}
