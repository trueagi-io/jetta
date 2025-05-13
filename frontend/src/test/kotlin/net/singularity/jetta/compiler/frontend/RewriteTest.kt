package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import net.singularity.jetta.compiler.frontend.ir.Lambda
import net.singularity.jetta.compiler.frontend.ir.Match
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        val rewriter = FunctionRewriter(messageCollector)
        val result = rewriter.rewrite(program)
        println(result)
        assertEquals(1, result.code.size)
        assertTrue(result.code[0] is FunctionDefinition)
        val func = result.code[0] as FunctionDefinition
        assertEquals(ArrowType(GroundedType.INT, GroundedType.INT, GroundedType.INT), func.arrowType)
        assertEquals("foo", func.name)
    }

    @Test
    fun `two patterns without args`() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "TwoPatterns0Args.metta",
                """
                (: foo (-> Int))
                (= (foo) 0)
                (= (foo) 1)
                """.trimIndent().replace('_', '$')
            ),
            messageCollector
        )
        val rewriter = FunctionRewriter(messageCollector)
        val result = rewriter.rewrite(program)
        println(result)
        assertEquals(1, result.code.size)
        assertTrue(result.code[0] is FunctionDefinition)
        val func = result.code[0] as FunctionDefinition
        assertTrue(func.body is Match)
        val match = func.body as Match
        assertEquals(2, match.branches.size)
        assertEquals(0, func.params.size)
    }

    @Test
    fun `two patterns with 1 arg`() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "TwoPatterns1Arg.metta",
                """
                (: foo (-> Int Int))
                (= (foo 10) 0)
                (= (foo _x) (+ _x 1))
                """.trimIndent().replace('_', '$')
            ),
            messageCollector
        )
        val rewriter = FunctionRewriter(messageCollector)
        val result = rewriter.rewrite(program)
        println(result)
        assertEquals(1, result.code.size)
        assertTrue(result.code[0] is FunctionDefinition)
        val func = result.code[0] as FunctionDefinition
        assertTrue(func.body is Match)
        val match = func.body as Match
        assertEquals(2, match.branches.size)
        assertEquals(1, func.params.size)
    }

    @Test
    fun lambda() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "Lambda.metta",
                """
                (: foo (-> Int Int (-> Int Int Int) Int))
                (= (foo _x _y _f) (_f _x _y))
                (foo 10 20 (\ (_x _y) (+ _x _y)))
                """.trimIndent().replace('_', '$')
            ),
            messageCollector
        )
        val rewriter = CompositeRewriter()
        rewriter.add { FunctionRewriter(messageCollector) }
        rewriter.add { LambdaRewriter(messageCollector) }
        val result = rewriter.rewrite(program)
        println(result)
    }

    @Test
    fun nestedLambdas() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "NestedLambdas.metta",
                """
                (: foo (-> Int Int (-> Int Int Int) Int))
                (= (foo _x _y _f) (_f _x _y))
                (: bar (-> Int Int))
                (= (bar _z)
                   (foo 10 20 (\ (_x _y) (+ _x _y _z (foo 10 20 (\ (_x _y) (+ _x _y _z)))
                   )))
                )
                """.trimIndent().replace('_', '$')
            ),
            messageCollector
        )
        val rewriter = CompositeRewriter()
        rewriter.add { FunctionRewriter(messageCollector) }
        rewriter.add { LambdaRewriter(messageCollector) }
        val result = rewriter.rewrite(program)
        println(result)
    }

    @Test
    fun annotations() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "Annotations.metta",
                """
                (@ foo multivalued)
                (= (foo) (list 1 2 3))
                
                (: f (-> Int Int Int))
                (= (f _x _y) (+ _x _y))
                
                (@ bar multivalued)
                (= (bar _x _y) (f (foo) (foo)))
                """.trimIndent().replace('_', '$')
            ),
            messageCollector
        )
        val rewriter = CompositeRewriter()
        rewriter.add { FunctionRewriter(messageCollector) }
        val result = rewriter.rewrite(program)
        println(result)
    }
}