package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.ResolvedSymbol
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalSymbolsTest : BaseFrontendTest() {
    val barFuncExample =
        """
        (: foo (-> Int Unit))
        (= (foo _x) (bar _x))
        (foo 10)
        """.trimIndent().replace('_', '$')

    @Test
    fun `resolve system function`() =
        resolve(
            "ResolveSystem.metta",
            barFuncExample
        ) { context ->
            val println = JvmMethod(
                owner = "SomeClass",
                name = "bar",
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

    @Test
    fun `incompatible parameter types`() =
        resolve(
            "Incompatible.metta",
            barFuncExample
        ) { context ->
            val println = JvmMethod(
                owner = "SomeClass",
                name = "bar",
                descriptor = "(Z)V"
            )
            val resolved = ResolvedSymbol(println, null, false)
            context.addSystemFunction(resolved)
        } .let { (_, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertEquals(1, messageCollector.list().size)
        }


    @Test
    fun `automatically boxed argument`() =
        resolve(
            "Autoboxing.metta",
            barFuncExample
        ) { context ->
            val println = JvmMethod(
                owner = "SomeClass",
                name = "bar",
                descriptor = "(Ljava/lang/Object;)V"
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