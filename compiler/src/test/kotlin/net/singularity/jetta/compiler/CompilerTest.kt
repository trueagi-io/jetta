package net.singularity.jetta.compiler

import net.singularity.jetta.compiler.frontend.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.singularity.jetta.compiler.backend.utils.toClasses

class CompilerTest {
    @Test
    fun `compile a single file`() = withCompiler {
        val name = "Hello"
        val result = compile(Source(
            "$name.metta",
            """
            (+ 1 1)    
            """.trimIndent()
        ))
        val classes = result.toClasses()
        assertEquals(1, classes.size)
        assertTrue(classes.contains(name))
    }
}