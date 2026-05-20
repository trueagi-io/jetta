package net.singularity.jetta.runtime.functions

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JettaCallSiteTest {

    @Test
    fun dispatchesToJettaFunction() {
        val sentinel = Any()
        val callable = JettaFunction { args -> args[0] }
        val result = JettaCallSite.dispatch(callable, arrayOf<Any?>(sentinel))
        assertSame(sentinel, result)
    }

    @Test
    fun dispatchOnUncallableHeadBuildsInertExpression() {
        val head = Symbol("Cons")
        val a = Symbol("X")
        val b = Symbol("Nil")
        val result = JettaCallSite.dispatch(head, arrayOf<Any?>(a, b))
        assertTrue(result is Expression, "expected Expression, got $result")
        assertEquals(3, result.atoms.size)
        assertSame(head, result.atoms[0])
        assertSame(a, result.atoms[1])
        assertSame(b, result.atoms[2])
    }

    @Test
    fun dispatchWrapsPrimitiveHeadInGrounded() {
        // A numeric or string sitting in head position is not callable; the
        // dispatcher should still wrap and emit data rather than crash.
        val result = JettaCallSite.dispatch(42, arrayOf<Any?>(Symbol("x")))
        assertTrue(result is Expression)
        assertTrue(result.atoms[0] is Grounded<*>)
        assertEquals(42, (result.atoms[0] as Grounded<*>).value)
    }

    @Test
    fun dispatchWrapsPrimitiveArgsInGrounded() {
        val head = Symbol("inc")
        val result = JettaCallSite.dispatch(head, arrayOf<Any?>(5))
        assertTrue(result is Expression)
        assertTrue(result.atoms[1] is Grounded<*>)
        assertEquals(5, (result.atoms[1] as Grounded<*>).value)
    }

    @Test
    fun bootstrapReturnsConstantCallSite() {
        val lookup = MethodHandles.lookup()
        val invokedType = MethodType.methodType(
            Any::class.java,
            Any::class.java,
            Array<Any?>::class.java,
        )
        val cs = JettaCallSite.bootstrap(lookup, "apply", invokedType)
        val callable = JettaFunction { args -> (args[0] as Int) + (args[1] as Int) }
        val sum = cs.target.invokeWithArguments(callable, arrayOf<Any?>(3, 4))
        assertEquals(7, sum)
    }

    @Test
    fun bootstrapTargetHandlesDataConstruction() {
        val lookup = MethodHandles.lookup()
        val invokedType = MethodType.methodType(
            Any::class.java,
            Any::class.java,
            Array<Any?>::class.java,
        )
        val cs = JettaCallSite.bootstrap(lookup, "apply", invokedType)
        val head = Symbol("Just")
        val out = cs.target.invokeWithArguments(head, arrayOf<Any?>(Symbol("v")))
        assertTrue(out is Expression)
    }
}
