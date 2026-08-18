package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A lambda inside QUOTED data. An unresolved head makes its whole application inert, and a `let` is
 * an applied lambda by then, so `(unknown-head (let $y $x $y))` put a lambda into a quoted
 * `Expression` — whose payload is `Atom[]`, which a `JettaFunction` is not: `ArrayStoreException`
 * the moment the quote was built, with nothing in the source hinting a lambda was involved.
 *
 * Two things now hold. An APPLIED lambda in an applicative position is evaluated like any other
 * call, so the data holds its VALUE — `(unknown-head 5)`, which is what the reference interpreter
 * answers. And a lambda that genuinely stays data is wrapped in `Grounded`, the wrapper for a value
 * of any type, so it can be stored at all.
 */
class QuotedLambdaTest : GeneratorTestBase() {
    private fun String.d() = replace('_', '$')

    /** The `let` under an unresolved head: evaluated, not left as a function object. */
    @Test
    fun `an applied lambda in quoted data is evaluated`() {
        val code = """
            (= (g _x) (unknown-head (let _y _x _y)))
        """.trimIndent().d()
        compile("QuotedLambdaLet.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, mc) ->
                assertTrue(mc.list().isEmpty(), mc.list().toString())
                val cls = result.toMap().toClasses()["QuotedLambdaLet"]!!
                JettaProgram.init("QuotedLambdaLet")
                val out = cls.getMethod("g", Atom::class.java).invoke(null, Grounded(5))
                assertEquals("(unknown-head 5)", out.toString())
            }
    }

    /** A `let` whose bound value is itself a call — the same shape one level deeper. */
    @Test
    fun `an applied lambda over a call in quoted data is evaluated`() {
        val code = """
            (: inc (-> Int Int))
            (= (inc _n) (+ _n 1))
            (= (g _x) (unknown-head (let _y (inc _x) _y)))
        """.trimIndent().d()
        compile("QuotedLambdaCall.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, mc) ->
                assertTrue(mc.list().isEmpty(), mc.list().toString())
                val cls = result.toMap().toClasses()["QuotedLambdaCall"]!!
                JettaProgram.init("QuotedLambdaCall")
                // Look the method up by NAME: `g`'s parameter type is whatever inference made of it
                // (it feeds `inc`), and the point here is the quoted body, not the signature.
                val g = cls.declaredMethods.first { it.name == "g" }
                val out = g.invoke(null, Grounded(5))
                assertEquals("(unknown-head 6)", out.toString())
            }
    }
}
