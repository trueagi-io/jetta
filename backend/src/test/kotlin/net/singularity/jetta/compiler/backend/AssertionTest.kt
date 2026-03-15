package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssertionTest : GeneratorTestBase() {
    @Ignore
    @Test
    fun `assertions compile and main class loads`() {
        compile(
            "AssertionCompilation.metta",
            $$"""
                (Frog Sam)
                (= (frog $x) (match &self (Frog $x) T))

                !(assertEqualToResult
                   (Frog Sam)
                   ((Frog Sam)))

                !(assertEqual
                   (frog Sam)
                   (T))

                !(assertEqualToResult
                   (frog Fritz)
                   ())
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("AssertionCompilation")
            assertEquals(1, classes.size)

            val main = classes["AssertionCompilation"]!!.getMethod("__main")
            main.invoke(null)
        }
    }
}