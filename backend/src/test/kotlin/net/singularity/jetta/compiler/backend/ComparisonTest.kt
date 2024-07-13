package net.singularity.jetta.compiler.backend

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComparisonTest : GeneratorTestBase() {
    @Test
    fun lt() =
        compile(
            "Lt.metta",
            """
                (: foo (-> Int Int Boolean))
                (= (foo _x _y _f) (< _x _y))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo", Int::class.java, Int::class.java)
            assertFalse(method.invoke(null, 2, 1) as Boolean)
            assertFalse(method.invoke(null, 2, 2) as Boolean)
            assertTrue(method.invoke(null, 1, 2) as Boolean)
        }

    @Test
    fun gt() =
        compile(
            "Gt.metta",
            """
                (: foo (-> Int Int Boolean))
                (= (foo _x _y _f) (> _x _y))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo", Int::class.java, Int::class.java)
            assertTrue(method.invoke(null, 2, 1) as Boolean)
            assertFalse(method.invoke(null, 2, 2) as Boolean)
            assertFalse(method.invoke(null, 1, 2) as Boolean)
        }

    @Test
    fun le() =
        compile(
            "Le.metta",
            """
                (: foo (-> Int Int Boolean))
                (= (foo _x _y _f) (<= _x _y))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo", Int::class.java, Int::class.java)
            assertFalse(method.invoke(null, 2, 1) as Boolean)
            assertTrue(method.invoke(null, 2, 2) as Boolean)
            assertTrue(method.invoke(null, 1, 2) as Boolean)
        }

    @Test
    fun ge() =
        compile(
            "Ge.metta",
            """
                (: foo (-> Int Int Boolean))
                (= (foo _x _y _f) (>= _x _y))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo", Int::class.java, Int::class.java)
            assertTrue(method.invoke(null, 2, 1) as Boolean)
            assertTrue(method.invoke(null, 2, 2) as Boolean)
            assertFalse(method.invoke(null, 1, 2) as Boolean)
        }

    @Test
    fun eq() =
        compile(
            "Eq.metta",
            """
                (: foo (-> Int Int Boolean))
                (= (foo _x _y _f) (== _x _y))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo", Int::class.java, Int::class.java)
            assertFalse(method.invoke(null, 2, 1) as Boolean)
            assertTrue(method.invoke(null, 2, 2) as Boolean)
            assertFalse(method.invoke(null, 1, 2) as Boolean)
        }

    @Test
    fun neq() =
        compile(
            "Neq.metta",
            """
                (: foo (-> Int Int Boolean))
                (= (foo _x _y _f) (!= _x _y))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo", Int::class.java, Int::class.java)
            assertTrue(method.invoke(null, 2, 1) as Boolean)
            assertFalse(method.invoke(null, 2, 2) as Boolean)
            assertTrue(method.invoke(null, 1, 2) as Boolean)
        }
}