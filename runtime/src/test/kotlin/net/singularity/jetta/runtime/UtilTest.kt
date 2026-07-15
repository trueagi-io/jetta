package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.runtime.functions.JettaFunction
import kotlin.test.Test
import kotlin.test.assertEquals

class UtilTest {

    /**
     * A JettaFunction whose compiled body has no applicable clause hands back `null`
     * (rather than an empty list). The flat-map combinator must treat that branch as
     * producing no results — dropping it — instead of throwing a NullPointerException.
     * Regression for BackchainWho, whose `(ift (deduce …) $x)` drives `ift` with a
     * non-`T` first argument on the branches that fail to prove: exceptions must never
     * be part of normal non-deterministic control flow.
     */
    @Test
    fun `simpleFlatMap drops a null-returning branch instead of throwing`() {
        val f = JettaFunction { args ->
            if ((args[0] as Symbol).name == "T") listOf(Symbol("ok")) else null
        }
        val result = simpleFlatMap(f, listOf(Symbol("T"), Symbol("F"), Symbol("T")))
        assertEquals(listOf("ok", "ok"), result.map { (it as Symbol).name })
    }

    @Test
    fun `simpleFlatMap flattens per-branch list results`() {
        val f = JettaFunction { args -> listOf(args[0], args[0]) }
        val result = simpleFlatMap(f, listOf(Symbol("a"), Symbol("b")))
        assertEquals(listOf("a", "a", "b", "b"), result.map { (it as Symbol).name })
    }
}
