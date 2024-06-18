package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.FunctionRewriter
import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RewriteTest : BaseFrontendTest() {
    @Test
    fun singleLinePattern() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "SimpleAtoms.metta",
                """
                (: foo (-> Int Int Int))
                (= (foo _x _y) (+ _x _y 1))
                """.trimIndent().replace('_', '$')
            ),
            messageCollector
        )
        val rewriter = FunctionRewriter()
        val result = rewriter.rewrite(program)
        println(result)
        assertEquals(1, result.code.size)
        assertTrue(result.code[0] is FunctionDefinition)
        val func = result.code[0] as FunctionDefinition
        assertEquals(ArrowType(GroundedType.INT, GroundedType.INT, GroundedType.INT), func.arrowType)
        assertEquals("foo", func.name)
    }
}