package net.singularity.jetta.compiler

import net.singularity.jetta.compiler.frontend.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompilerIntegrationTest {
    @Test
    fun `compile a single file`() = withCompiler {
        val name = "Hello"
        val result = compile(Source(
            "$name.metta",
            """
            (+ 1 1)    
            """.trimIndent()
        ))
        assertEquals(1, result.size)
        assertTrue(result.contains(name))
    }

    @Test
    fun `compile multiple files in the same package`() = withCompiler {
        val foo = "Foo"
        val bar = "Bar"
        val result = compile(Source(
            "$foo.metta",
            """
            (@ foo export)
            (: foo (-> Int Int))
            (= (foo _x) (+ _x 1))
            """.trimMargin().replace('_', '$')
        ),
        Source(
            "$bar.metta",
            """
            (: bar (-> Int Int)) 
            (= (bar _x) (foo _x))
            """.trimMargin().replace('_', '$')
        ))
        assertEquals(2, result.size)
        assertTrue(result.contains(foo))
        assertTrue(result.contains(bar))
    }
}