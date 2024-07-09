package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
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
        rewriter.add(FunctionRewriter(messageCollector))
        rewriter.add(LambdaRewriter(messageCollector))
        val result = rewriter.rewrite(program)
        println(result)
    }

    // signature (ILkotlin/jvm/functions/Function5<-Ljava/lang/Integer;-Ljava/lang/Integer;-Ljava/lang/Integer;-Ljava/lang/Integer;-Ljava/lang/Integer;Ljava/lang/Integer;>;)V
    // declaration: void foo(int, kotlin.jvm.functions.Function5<? super java.lang.Integer, ? super java.lang.Integer, ? super java.lang.Integer, ? super java.lang.Integer, ? super java.lang.Integer, java.lang.Integer>)

    fun foo(x: Int, f: (Int, Int) -> Int) {
        println(f(x, x))
    }

    fun bar(x: Int) {
        foo(5) { x1, x2 ->
            x1 + x2 + x
        }
    }
}