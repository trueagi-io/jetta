package net.singularity.jetta.runtime.functions

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Special
import net.singularity.jetta.compiler.frontend.ir.Symbol
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Step-1 MVP smoke tests for the JIT-eval primitive: prove the
 * compile -> load -> invoke loop end to end on expressions over SYSTEM functions only.
 * The input is built directly as IR data (no parser), mirroring how the compiled
 * `(eval (quote …))` call site hands an inert Expression to [JettaJit.eval].
 */
class JettaJitTest {

    @Test
    fun `evaluates a system arithmetic expression`() {
        // (+ 1 2)
        val code = Expression(listOf(Special("+"), Grounded(1), Grounded(2)))
        val result = JettaJit.eval(code)
        assertEquals(listOf<Any?>(3), result.map { (it as Grounded<*>).value })
    }

    @Test
    fun `evaluates nested arithmetic`() {
        // (* (+ 1 2) 4) -> 12
        val code = Expression(
            listOf(
                Special("*"),
                Expression(listOf(Special("+"), Grounded(1), Grounded(2))),
                Grounded(4)
            )
        )
        val result = JettaJit.eval(code)
        assertEquals(listOf<Any?>(12), result.map { (it as Grounded<*>).value })
    }

    @Test
    fun `evaluates a multivalued superpose into a bag`() {
        // (superpose (red yellow green)) -> [red, yellow, green] — the idiomatic
        // symbol-tuple form (cf. b4_nondeterm); proves the multivalued bag path.
        val code = Expression(
            listOf(
                Symbol("superpose"),
                Expression(listOf(Symbol("red"), Symbol("yellow"), Symbol("green")))
            )
        )
        val result = JettaJit.eval(code)
        assertEquals(listOf("red", "yellow", "green"), result.map { (it as Symbol).name })
    }

    @Test
    fun `repeated eval of the same shape hits the cache`() {
        val code = Expression(listOf(Special("+"), Grounded(40), Grounded(2)))
        val first = JettaJit.eval(code)
        // A structurally-equal but distinct instance must resolve to the cached class.
        val again = JettaJit.eval(Expression(listOf(Special("+"), Grounded(40), Grounded(2))))
        assertEquals(first.map { (it as Grounded<*>).value }, again.map { (it as Grounded<*>).value })
        assertEquals(listOf<Any?>(42), first.map { (it as Grounded<*>).value })
    }
}
