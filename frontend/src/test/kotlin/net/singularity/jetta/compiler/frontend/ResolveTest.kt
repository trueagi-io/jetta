package net.singularity.jetta.compiler.frontend

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
            assertEquals(GroundedType.INT, (func.body.atoms[1] as Variable).type)
            assertEquals(GroundedType.INT, (func.body.atoms[2] as Variable).type)
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
            val call = func.body
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
}