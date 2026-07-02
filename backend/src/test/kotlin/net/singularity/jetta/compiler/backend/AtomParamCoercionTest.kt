package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.Grounded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Passing a grounded VALUE (Int/Double/String) to an `Atom`-typed parameter. The verifier
 * rejects a bare box (`Integer`) where an `Atom` is expected, so the call site must wrap
 * the value in a `Grounded` (which IS an `Atom`) — see `FunctionGenerator.generateCall` /
 * `generateGroundedValueArg`. This is the coercion that lets an Int literal reach the
 * `Atom` parameter of a higher-order function such as `(apply inc 5)`.
 */
class AtomParamCoercionTest : GeneratorTestBase() {
    private fun String.d() = replace('_', '$')

    @Test
    fun `int literal passed to an Atom parameter is wrapped in a Grounded`() {
        // caller calls id with the literal 5; id's parameter is Atom, so 5 must be
        // Grounded-wrapped at the call site. Before the fix this VerifyError'd
        // (integer not assignable to Atom).
        val code = """
            (: id (-> Atom Atom))
            (= (id _x) _x)
            (= (caller) (id 5))
        """.trimIndent().d()
        compile("AtomParam.metta", code).let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), "no diagnostics expected: ${mc.list()}")
            val out = result[0].getClass().getMethod("caller").invoke(null)
            assertTrue(out is Grounded<*>, "expected a Grounded Atom, got ${out?.javaClass}")
            assertEquals(5, (out as Grounded<*>).value)
        }
    }
}
