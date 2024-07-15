package net.singularity.jetta.compiler.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArithmeticTest : GeneratorTestBase() {
    @Test
    fun divideIntInt() =
        compile(
            "DivideIntInt.metta",
            """
                (: foo (-> Int Int Double))
                (= (foo _x _y _f) (/ _x _y))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo", Int::class.java, Int::class.java)
            assertEquals(2.0, method.invoke(null, 2, 1))
            assertEquals(1.0, method.invoke(null, 2, 2))
            assertEquals(0.5, method.invoke(null, 1, 2))
        }

    @Test
    fun divideIntDouble() =
        compile(
            "DivideIntDouble.metta",
            """
                (: foo (-> Int Double Double))
                (= (foo _x _y _f) (/ _x _y))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo", Int::class.java, Double::class.java)
            assertEquals(2.0, method.invoke(null, 2, 1.0))
            assertEquals(1.0, method.invoke(null, 2, 2.0))
            assertEquals(0.5, method.invoke(null, 1, 2.0))
        }

    @Test
    fun divideDoubleInt() =
        compile(
            "DivideDoubleInt.metta",
            """
                (: foo (-> Double Int Double))
                (= (foo _x _y _f) (/ _x _y))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo", Double::class.java, Int::class.java)
            assertEquals(2.0, method.invoke(null, 2.0, 1))
            assertEquals(1.0, method.invoke(null, 2.0, 2))
            assertEquals(0.5, method.invoke(null, 1.0, 2))
        }

    @Test
    fun divideDoubleDouble() =
        compile(
            "DivideDoubleDouble.metta",
            """
                (: foo (-> Double Double Double))
                (= (foo _x _y _f) (/ _x _y))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo", Double::class.java, Double::class.java)
            assertEquals(2.0, method.invoke(null, 2.0, 1.0))
            assertEquals(1.0, method.invoke(null, 2.0, 2.0))
            assertEquals(0.5, method.invoke(null, 1.0, 2.0))
        }

    @Test
    fun div() =
        compile(
            "Div.metta",
            """
                (: foo (-> Int Int Int))
                (= (foo _x _y _f) (div _x _y))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo", Int::class.java, Int::class.java)
            assertEquals(2, method.invoke(null, 2, 1))
            assertEquals(1, method.invoke(null, 3, 2))
            assertEquals(2, method.invoke(null, 5, 2))
        }

    @Test
    fun mod() =
        compile(
            "Div.metta",
            """
                (: foo (-> Int Int Int))
                (= (foo _x _y _f) (mod _x _y))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo", Int::class.java, Int::class.java)
            assertEquals(0, method.invoke(null, 2, 1))
            assertEquals(1, method.invoke(null, 3, 2))
            assertEquals(1, method.invoke(null, 5, 2))
        }
}