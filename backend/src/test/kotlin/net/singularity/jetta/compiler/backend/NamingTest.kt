package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamingTest : GeneratorTestBase() {
    @Test
    fun variablesWithIds() =
        compile(
            "VariablesWithIds.metta",
            """
                (: foo (-> Int Int Int))
                (= (foo _x#1 _x#2) (+ _x#1 _x#2))
                (foo 10 20)
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["VariablesWithIds"]!!.getMethod("__main").invoke(null)
            assertEquals(30, value)
        }

    @Test
    fun dashInNames() =
        compile(
            "DashInNames.metta",
            """
                (: foo-bar (-> Int Int Int))
                (= (foo-bar _x _y) (+ _x _y))
                (foo-bar 10 20)
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["DashInNames"]!!.getMethod("__main").invoke(null)
            assertEquals(30, value)
        }
}