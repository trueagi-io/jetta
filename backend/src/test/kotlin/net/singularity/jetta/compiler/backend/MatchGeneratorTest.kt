package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchGeneratorTest : GeneratorTestBase() {
    @Test
    fun `0 args match, 2 branches`() =
        compile(
            "TwoBranches0Args.metta",
            """
                (: foo (-> Int))
                (= (foo) 0)
                (= (foo) 1)
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["TwoBranches0Args"]!!.getMethod("foo").invoke(null)
            assertEquals(listOf(0, 1), value)
        }

    @Test
    fun `1 args match, 2 branches`() =
        compile(
            "TwoBranches1Arg.metta",
            """
                (: foo (-> Int Int))
                (= (foo 10) 0)
                (= (foo _x) (+ _x 1))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["TwoBranches1Arg"]!!.getMethod("foo", Int::class.java).invoke(null, 10)
            assertEquals(listOf(0, 11), value)
        }
}