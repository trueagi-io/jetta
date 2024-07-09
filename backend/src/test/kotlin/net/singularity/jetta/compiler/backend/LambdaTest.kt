package net.singularity.jetta.compiler.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LambdaTest : GeneratorTestBase() {
    @Test
    fun simple() =
        compile(
            "Lambda.metta",
            """
                (: foo (-> Int Int (-> Int Int Int) Int))
                (= (foo _x _y _f) (_f _x _y))
                (foo 10 20 (\ (_x _y) (+ _x _y)))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(2, classes.size)
            val value = classes["Lambda"]!!.getMethod("__main").invoke(null)
            assertEquals(30, value)
        }
}