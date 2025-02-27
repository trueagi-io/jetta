package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import net.singularity.jetta.compiler.frontend.resolve.messages.CannotResolveSymbolMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResolveTest : BaseFrontendTest() {
    @Test
    fun expressionTypes() =
        resolve(
            "SimpleAtoms.metta",
            """
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y 1))
            """.trimIndent().replace('_', '$')
        ).let { (result, _) ->
            val func = result.code[0] as FunctionDefinition
            assertEquals(GroundedType.INT, func.body.type)
            assertEquals(GroundedType.INT, ((func.body as Expression).atoms[1] as Variable).type)
            assertEquals(GroundedType.INT, ((func.body as Expression).atoms[2] as Variable).type)
        }

    @Test
    fun call() =
        resolve(
            "FunctionCall.metta",
            """
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y 1))
            
            (: bar (-> Int))
            (= (bar) (foo 1 2))
            """.trimIndent().replace('_', '$')
        ).let { (result, _) ->
            assertEquals(2, result.code.size)
            val func = result.code[1] as FunctionDefinition
            val call = func.body as Expression
            assertNotNull(call.resolved)
            assertEquals(JvmMethod("FunctionCall", "foo", "(II)I"), call.resolved!!.jvmMethod)
        }

    @Test
    fun cannotResolveSymbol() =
        resolve(
            "CannotResolveSymbol.metta",
            """
            (: bar (-> Int))
            (= (bar) (foo 1 2))
            """.trimIndent().replace('_', '$')
        ).let { (_, messageCollector) ->
            assertEquals(1, messageCollector.list().size)
            assertTrue(messageCollector.list()[0] is CannotResolveSymbolMessage)
            val message = messageCollector.list()[0] as CannotResolveSymbolMessage
            assertEquals("foo", message.symbol)
        }

    @Test
    fun factorial() =
        resolve(
            "Factorial.metta",
            """
            (: factorial (-> Int Int))
            (= (factorial _n)
                (if (== _n 0) 1
                   (* _n (factorial (- _n 1)))))
            """.trimIndent().replace('_', '$')
        ).let { (_, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertEquals(0, messageCollector.list().size)
        }

    @Test
    fun lambda() =
        resolve(
            "Lambda.metta",
            """
                (: foo (-> Int Int (-> Int Int Int) Int))
                (= (foo _x _y _f) (_f _x _y))
                (foo 10 20 (\ (_x _y) (+ _x _y)))
                """.trimIndent().replace('_', '$')
        ).let { (_, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertEquals(0, messageCollector.list().size)
        }


    @Test
    fun `resolve dependencies in multiple files`() =
        resolveMultiple(
            Source(
                "Foo.metta",
                """(@ foo export)
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
        ).let { (_, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertEquals(0, messageCollector.list().size)
        }
}