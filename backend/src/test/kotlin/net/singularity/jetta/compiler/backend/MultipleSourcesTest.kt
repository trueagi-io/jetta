package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultipleSourcesTest : GeneratorTestBase() {
    @Test
    fun `compile multiple sources`() {
        compileMultiple(
            Source(
                "Foo.metta",
                """
                (@ foo export)
                (: foo (-> Int Int))
                (= (foo _x) (+ _x 1))
                """.trimMargin().replace('_', '$')
            ),
            Source(
                "Bar.metta",
                """
                (: bar (-> Int Int)) 
                (= (bar _x) (foo _x))
                """.trimMargin().replace('_', '$')
            )
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(2, classes.size)
            val value = classes["Bar"]!!.getMethod("bar", Int::class.java).invoke(null, 1)
            assertEquals(2, value)
        }
    }
}