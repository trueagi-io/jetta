package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.Expression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The return-side twin of [ExpressionParamCoercionTest]. A body whose static type is the erased
 * `Atom` under a declared `Expression` return needs the cast `ARETURN` will not infer — without it
 * the method fails verification and takes its whole class down with it, so these tests prove the
 * point by loading and invoking.
 *
 * The shape is the reference `stdlib.metta`'s own `cdr-atom`: `(: cdr-atom (-> Expression
 * Expression))` with a body over `decons-atom` and `unify` that resolves to `Atom`. See
 * `FunctionGenerator.narrowReturnToExpression`.
 */
class ExpressionReturnCoercionTest : GeneratorTestBase() {
    private fun String.d() = replace('_', '$')

    /** An inert application — data, hence `Atom` — returned from an `Expression`-declared function. */
    @Test
    fun `an inert application is returned from an Expression-declared function`() {
        val code = """
            (: pick (-> Expression Expression))
            (= (pick _e) (wrap _e))
            (= (caller) (pick (A B)))
        """.trimIndent().d()
        compile("ExpressionReturnInert.metta", code).let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), "no diagnostics expected: ${mc.list()}")
            val out = result[0].getClass().getMethod("caller").invoke(null)
            assertTrue(out is Expression, "expected an Expression, got ${out?.javaClass}")
            assertTrue(
                (out as Expression).atoms.first().toString() == "wrap",
                "expected the inert (wrap …) term, got $out"
            )
        }
    }

    /**
     * The `cdr-atom` shape: a `let` over a grounded op, then a `unify` as the result.
     *
     * `unify` used to be an unknown head here, so the whole body compiled as inert DATA — which is
     * what made this the return-coercion case. Now that `unify` is lowered onto the multivalued
     * `unifyMatch` helper the body is a real call and the function returns a result BAG, so the
     * `Expression`-return cast this file exists for is pinned by the first test alone. What this one
     * still pins is the reference shape compiling and computing the tail end to end.
     */
    @Test
    fun `the reference cdr-atom shape returns through a let`() {
        val code = """
            (: mycdr (-> Expression Expression))
            (= (mycdr _atom)
              (let _ht (decons-atom _atom) (unify (_x _tail) _ht _tail (Error _atom "empty"))))
            (= (caller) (mycdr (A B)))
        """.trimIndent().d()
        compile("ExpressionReturnCdr.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, mc) ->
                assertTrue(mc.list().isEmpty(), "no diagnostics expected: ${mc.list()}")
                val out = result[0].getClass().getMethod("caller").invoke(null)
                val bag = out as? List<*> ?: error("expected a result bag, got ${out?.javaClass}")
                assertEquals(1, bag.size, "expected one result, got $bag")
                assertEquals("(B)", bag[0].toString(), "expected the tail (B), got ${bag[0]}")
            }
    }
}
