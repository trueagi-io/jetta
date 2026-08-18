package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.Symbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An argument that is statically wider than an `Expression` parameter — an erased `Atom`, or an
 * `Object` — must be CHECKCAST at the call site. Without it `INVOKESTATIC` against a
 * `…/ir/Expression;` parameter fails CLASS-LOAD verification, so nothing in the class runs; these
 * tests therefore prove the point simply by loading and invoking.
 *
 * The shape comes from the reference `stdlib.metta`: `switch-internal`, declared
 * `(-> Atom Expression Atom)`, calls `switch-minimal` with the `$tail` it destructured out of
 * `(($pattern $template) $tail)` — an `Atom` local. See
 * `FunctionGenerator.narrowArgumentToExpression`.
 */
class ExpressionParamCoercionTest : GeneratorTestBase() {
    private fun String.d() = replace('_', '$')

    /** A destructured pattern binding (an `Atom` local) reaching an `Expression` parameter. */
    @Test
    fun `a destructured binding reaches an Expression parameter`() {
        val code = """
            (: g (-> Atom Expression Atom))
            (= (g _a _cases) _a)
            (: h (-> Atom Expression Atom))
            (= (h _a ((_p _t) _tail)) (g _a _tail))
            (= (caller) (h X ((P T) (Q R))))
        """.trimIndent().d()
        compile("ExpressionParamDestructured.metta", code).let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), "no diagnostics expected: ${mc.list()}")
            val out = result[0].getClass().getMethod("caller").invoke(null)
            assertEquals("X", (out as Symbol).name)
        }
    }

    /**
     * A data expression built at the call site, with no destructuring anywhere: the same widening
     * reached from `Object`, since a nested tuple is materialized through the generic data path.
     */
    @Test
    fun `a data expression literal reaches an Expression parameter`() {
        val code = """
            (: g (-> Atom Expression Atom))
            (= (g _a _cases) _a)
            (= (caller) (g Y ((P T) (Q R))))
        """.trimIndent().d()
        compile("ExpressionParamLiteral.metta", code).let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), "no diagnostics expected: ${mc.list()}")
            val out = result[0].getClass().getMethod("caller").invoke(null)
            assertEquals("Y", (out as Symbol).name)
        }
    }
}
