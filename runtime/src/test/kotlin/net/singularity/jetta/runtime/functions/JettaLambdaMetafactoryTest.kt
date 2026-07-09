package net.singularity.jetta.runtime.functions

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class JettaLambdaMetafactoryTest {
    // Lambda bodies live as static methods on this class; the bootstrap
    // is given direct MethodHandles into them.

    companion object {
        @JvmStatic
        fun constantString(): String = "hello"

        @JvmStatic
        fun identityAtom(x: Any?): Any? = x

        @JvmStatic
        fun addInts(x: Int, y: Int): Int = x + y

        @JvmStatic
        fun addCaptured(captured: Int, x: Int): Int = captured + x

        @JvmStatic
        fun joinWith(sep: String, a: String, b: String): String = "$a$sep$b"

        @JvmStatic
        fun mixed(longCap: Long, doubleCap: Double, x: Int): Double = longCap + doubleCap + x

        @JvmStatic
        fun consume(@Suppress("UNUSED_PARAMETER") x: Any?) {
            // void return
        }
    }

    private val lookup = MethodHandles.lookup()

    private fun build(
        bodyName: String,
        bodyType: MethodType,
        invokedType: MethodType,
    ): java.lang.invoke.MethodHandle {
        val impl = lookup.findStatic(JettaLambdaMetafactoryTest::class.java, bodyName, bodyType)
        val cs = JettaLambdaMetafactory.bootstrap(lookup, "apply", invokedType, impl)
        return cs.target
    }

    @Test
    fun noCapturesNoArgs() {
        val target = build(
            "constantString",
            MethodType.methodType(String::class.java),
            MethodType.methodType(JettaFunction::class.java),
        )
        val fn = target.invokeWithArguments() as JettaFunction
        assertEquals("hello", fn.apply(emptyArray()))
    }

    @Test
    fun singleReferenceArgNoCaptures() {
        val target = build(
            "identityAtom",
            MethodType.methodType(Any::class.java, Any::class.java),
            MethodType.methodType(JettaFunction::class.java),
        )
        val fn = target.invokeWithArguments() as JettaFunction
        val payload = Any()
        assertEquals(payload, fn.apply(arrayOf<Any?>(payload)))
    }

    @Test
    fun primitiveArgsNoCaptures() {
        val target = build(
            "addInts",
            MethodType.methodType(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            MethodType.methodType(JettaFunction::class.java),
        )
        val fn = target.invokeWithArguments() as JettaFunction
        assertEquals(7, fn.apply(arrayOf<Any?>(3, 4)))
    }

    @Test
    fun primitiveCaptureAndArg() {
        val target = build(
            "addCaptured",
            MethodType.methodType(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            MethodType.methodType(JettaFunction::class.java, Int::class.javaPrimitiveType),
        )
        val fn = target.invokeWithArguments(10) as JettaFunction
        assertEquals(15, fn.apply(arrayOf<Any?>(5)))
    }

    @Test
    fun referenceCaptureAndArgs() {
        val target = build(
            "joinWith",
            MethodType.methodType(String::class.java, String::class.java, String::class.java, String::class.java),
            MethodType.methodType(JettaFunction::class.java, String::class.java),
        )
        val fn = target.invokeWithArguments("-") as JettaFunction
        assertEquals("foo-bar", fn.apply(arrayOf<Any?>("foo", "bar")))
    }

    @Test
    fun mixedTypesLongDoubleInt() {
        val target = build(
            "mixed",
            MethodType.methodType(
                Double::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
            MethodType.methodType(
                JettaFunction::class.java,
                Long::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
            ),
        )
        val fn = target.invokeWithArguments(100L, 2.5) as JettaFunction
        assertEquals(106.5, fn.apply(arrayOf<Any?>(4)))
    }

    @Test
    fun voidReturnBoxesToNull() {
        val target = build(
            "consume",
            MethodType.methodType(Void.TYPE, Any::class.java),
            MethodType.methodType(JettaFunction::class.java),
        )
        val fn = target.invokeWithArguments() as JettaFunction
        assertEquals(null, fn.apply(arrayOf<Any?>("ignored")))
    }

    @Test
    fun freshInstancePerCallSiteInvocation() {
        val target = build(
            "addCaptured",
            MethodType.methodType(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            MethodType.methodType(JettaFunction::class.java, Int::class.javaPrimitiveType),
        )
        val a = target.invokeWithArguments(1) as JettaFunction
        val b = target.invokeWithArguments(2) as JettaFunction
        assertNotSame(a, b)
        assertEquals(11, a.apply(arrayOf<Any?>(10)))
        assertEquals(12, b.apply(arrayOf<Any?>(10)))
    }

    @Test
    fun rejectsNonStaticImpl() {
        // For Phase 0b only static lambda bodies are supported.
        val virtualImpl = lookup.findVirtual(
            String::class.java,
            "length",
            MethodType.methodType(Int::class.javaPrimitiveType),
        )
        val invokedType = MethodType.methodType(JettaFunction::class.java, String::class.java)
        val ex = runCatching {
            JettaLambdaMetafactory.bootstrap(lookup, "apply", invokedType, virtualImpl)
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "expected IllegalArgumentException, got $ex")
    }
}
