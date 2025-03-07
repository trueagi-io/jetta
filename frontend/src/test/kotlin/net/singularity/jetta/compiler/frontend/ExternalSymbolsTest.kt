package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.ResolvedSymbol
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalSymbolsTest : BaseFrontendTest() {
    @Test
    fun `resolve println function`() =
        resolve(
            "Println.metta",
            """
                (: foo (-> Int Unit))
                (= (foo _x) (println _x))
                (foo 10)
                """.trimIndent().replace('_', '$')
        ) { context ->
            val println = JvmMethod(
                owner = "SomeClass",
                name = "println",
                descriptor = "(I)V"
            )
            val resolved = ResolvedSymbol(println, null, false)
            context.addSystemFunction(resolved)
        } .let { (_, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertEquals(0, messageCollector.list().size)
        }

}