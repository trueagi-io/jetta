package net.singularity.jetta.compiler

import net.singularity.jetta.compiler.frontend.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompilerCLITest {
    @Test
    fun `compile files in the same package`() {
        val sources = listOf(
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
        )
        val compiler = Compiler(listOf(), "/tmp")
        val (success, messages) = compiler.compileMultipleSources(sources)
        assertTrue(success)
        assertEquals(0, messages.size)
    }

    @Test
    fun `compile a println call`() {
        val sources = listOf(
            Source(
                "Println.metta",
                """
                (println (+ 1 1))
                """.trimMargin().replace('_', '$')
            )
        )
        val compiler = Compiler(listOf(), "/tmp")
        val (success, messages) = compiler.compileMultipleSources(sources)
        assertTrue(success)
        assertEquals(0, messages.size)
    }
}