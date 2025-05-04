package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.ResolvedSymbol
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import net.singularity.jetta.runtime.IO
import org.objectweb.asm.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringTest : GeneratorTestBase() {
    @Test
    fun `compile hello world`(): Unit =
        compile(
            "Hello.metta",
            """
                (println "Hello World")
                """.trimIndent().replace('_', '$')
        ) { context ->
            context.addSystemFunction(
                ResolvedSymbol(
                    JvmMethod(
                        owner = Type.getInternalName(IO::class.java),
                        name = "println",
                        descriptor = "(Ljava/lang/Object;)V"
                    ), null, false
                )
            )
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(1, classes.size)
            classes["Hello"]!!.getMethod("__main").invoke(null)
        }
}