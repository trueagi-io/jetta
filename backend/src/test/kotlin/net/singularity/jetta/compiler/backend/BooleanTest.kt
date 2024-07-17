package net.singularity.jetta.compiler.backend

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BooleanTest : GeneratorTestBase() {
    @Test
    fun not() =
        compile(
            "Not.metta",
            """
                (: foo (-> Boolean Boolean))
                (= (foo _x) (not _x))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo", Boolean::class.java)
            assertTrue(method.invoke(null, false) as Boolean)
            assertFalse(method.invoke(null, true) as Boolean)
        }

    @Test
    fun trueLiteral() =
        compile(
            "TrueLiteral.metta",
            """
                (: foo (-> Boolean))
                (= (foo) (not true))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo")
            assertFalse(method.invoke(null) as Boolean)
        }

    @Test
    fun falseLiteral() =
        compile(
            "FalseLiteral.metta",
            """
                (: foo (-> Boolean))
                (= (foo) (not false))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val method = result[0].getClass().getMethod("foo")
            assertTrue(method.invoke(null) as Boolean)
        }

}