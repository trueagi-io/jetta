package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.ResolvedSymbol
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import net.singularity.jetta.runtime.IO
import org.objectweb.asm.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalCallsTest : GeneratorTestBase() {
    @Test
    fun `call println with boxed int arg`() =
        compile(
            "Println.metta",
            """
            (println (+ 1 1))
            """.trimIndent().replace('_', '$')
        ) { context ->
            val println = JvmMethod(
                owner = Type.getInternalName(IO::class.java),
                name = "println",
                descriptor = "(Ljava/lang/Object;)V"
            )
            val resolved = ResolvedSymbol(println, null, false)
            context.addSystemFunction(resolved)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(1, classes.size)
            classes["Println"]!!.getMethod("__main").invoke(null)
            println()
        }

    @Test
    fun `call println with boxed double arg`() =
        compile(
            "Println.metta",
            """
            (println (+ 1.0 2.0))
            """.trimIndent().replace('_', '$')
        ) { context ->
            val println = JvmMethod(
                owner = Type.getInternalName(IO::class.java),
                name = "println",
                descriptor = "(Ljava/lang/Object;)V"
            )
            val resolved = ResolvedSymbol(println, null, false)
            context.addSystemFunction(resolved)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(1, classes.size)
            classes["Println"]!!.getMethod("__main").invoke(null)
            println()
        }
}