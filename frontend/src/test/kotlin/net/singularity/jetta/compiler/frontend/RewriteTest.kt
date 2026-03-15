package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import net.singularity.jetta.compiler.frontend.ir.Match
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.runtime.space.SpaceImpl
import kotlin.test.*

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
        val rewriter = FunctionRewriter(messageCollector, SpaceImpl())
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
        val rewriter = FunctionRewriter(messageCollector, SpaceImpl())
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
        val rewriter = FunctionRewriter(messageCollector, SpaceImpl())
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
        rewriter.add { FunctionRewriter(messageCollector, SpaceImpl()) }
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
        rewriter.add { FunctionRewriter(messageCollector, SpaceImpl()) }
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
        rewriter.add { FunctionRewriter(messageCollector, SpaceImpl()) }
        val result = rewriter.rewrite(program)
        println(result)
    }

    @Test
    fun `nested pattern produces destructure bindings`() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "NestedPattern.metta",
                """
                (= (foo (Bar _a _b)) (+ _a _b))
                (= (foo _x) _x)
                """.trimIndent().replace('_', '$')
            ),
            messageCollector
        )
        val rewriter = FunctionRewriter(messageCollector, SpaceImpl())
        val result = rewriter.rewrite(program)

        assertEquals(1, result.code.size)
        val func = result.code[0] as FunctionDefinition
        assertEquals("foo", func.name)
        assertEquals(1, func.params.size) // single formal param $var0

        assertTrue(func.body is Match)
        val match = func.body as Match
        assertEquals(2, match.branches.size)

        // First branch: (foo (Bar $a $b)) -> has destructure bindings
        val branch0 = match.branches[0]
        assertNotNull(branch0.cond) // condition checks $var0 == (Bar $a $b)
        assertEquals(2, branch0.destructuredBindings.size)

        val bindingA = branch0.destructuredBindings.find { it.originalName == "a" }
        assertNotNull(bindingA)
        assertEquals(0, bindingA.paramIndex)
        assertContentEquals(intArrayOf(1), bindingA.extractionPath)

        val bindingB = branch0.destructuredBindings.find { it.originalName == "b" }
        assertNotNull(bindingB)
        assertEquals(0, bindingB.paramIndex)
        assertContentEquals(intArrayOf(2), bindingB.extractionPath)

        // Second branch: (foo $x) -> no destructure bindings
        val branch1 = match.branches[1]
        assertTrue(branch1.destructuredBindings.isEmpty())
    }

    @Test
    fun `deeply nested pattern produces correct extraction paths`() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "DeepNestedPattern.metta",
                """
                (= (foo (Bar (Baz _x) _y)) _x)
                (= (foo _z) _z)
                """.trimIndent().replace('_', '$')
            ),
            messageCollector
        )
        val rewriter = FunctionRewriter(messageCollector, SpaceImpl())
        val result = rewriter.rewrite(program)

        val func = result.code[0] as FunctionDefinition
        val match = func.body as Match

        val branch0 = match.branches[0]
        assertEquals(2, branch0.destructuredBindings.size)

        // $x is at (Bar (Baz $x) $y) -> atoms[1].atoms[1] -> path [1, 1]
        val bindingX = branch0.destructuredBindings.find { it.originalName == "x" }
        assertNotNull(bindingX)
        assertEquals(0, bindingX.paramIndex)
        assertContentEquals(intArrayOf(1, 1), bindingX.extractionPath)

        // $y is at (Bar (Baz $x) $y) -> atoms[2] -> path [2]
        val bindingY = branch0.destructuredBindings.find { it.originalName == "y" }
        assertNotNull(bindingY)
        assertEquals(0, bindingY.paramIndex)
        assertContentEquals(intArrayOf(2), bindingY.extractionPath)
    }

    @Test
    fun `multiple params with nested patterns`() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "MultiParamNested.metta",
                """
                (= (foo (Pair _a _b) (Pair _c _d)) (+ _a _d))
                (= (foo _x _y) _x)
                """.trimIndent().replace('_', '$')
            ),
            messageCollector
        )
        val rewriter = FunctionRewriter(messageCollector, SpaceImpl())
        val result = rewriter.rewrite(program)

        val func = result.code[0] as FunctionDefinition
        assertEquals(2, func.params.size) // $var0, $var1
        val match = func.body as Match

        val branch0 = match.branches[0]
        assertEquals(4, branch0.destructuredBindings.size)

        // $a from param 0 at path [1]
        val bindingA = branch0.destructuredBindings.find { it.originalName == "a" }
        assertNotNull(bindingA)
        assertEquals(0, bindingA.paramIndex)
        assertContentEquals(intArrayOf(1), bindingA.extractionPath)

        // $b from param 0 at path [2]
        val bindingB = branch0.destructuredBindings.find { it.originalName == "b" }
        assertNotNull(bindingB)
        assertEquals(0, bindingB.paramIndex)
        assertContentEquals(intArrayOf(2), bindingB.extractionPath)

        // $c from param 1 at path [1]
        val bindingC = branch0.destructuredBindings.find { it.originalName == "c" }
        assertNotNull(bindingC)
        assertEquals(1, bindingC.paramIndex)
        assertContentEquals(intArrayOf(1), bindingC.extractionPath)

        // $d from param 1 at path [2]
        val bindingD = branch0.destructuredBindings.find { it.originalName == "d" }
        assertNotNull(bindingD)
        assertEquals(1, bindingD.paramIndex)
        assertContentEquals(intArrayOf(2), bindingD.extractionPath)
    }

    @Test
    fun `simple pattern has no destructure bindings`() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "SimplePatternNoBindings.metta",
                """
                (= (foo 10) 0)
                (= (foo _x) (+ _x 1))
                """.trimIndent().replace('_', '$')
            ),
            messageCollector
        )
        val rewriter = FunctionRewriter(messageCollector, SpaceImpl())
        val result = rewriter.rewrite(program)

        val func = result.code[0] as FunctionDefinition
        val match = func.body as Match
        match.branches.forEach { branch ->
            assertTrue(branch.destructuredBindings.isEmpty())
        }
    }

    @Test
    fun `backchain And pattern produces correct bindings`() {
        // This is the actual pattern from the backward chaining test
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "BackchainAnd.metta",
                """
                (= (deduce (And _a _b)) (And (deduce _a) (deduce _b)))
                (= (deduce _x) _x)
                """.trimIndent().replace('_', '$')
            ),
            messageCollector
        )
        val rewriter = FunctionRewriter(messageCollector, SpaceImpl())
        val result = rewriter.rewrite(program)

        val func = result.code[0] as FunctionDefinition
        assertEquals("deduce", func.name)
        assertEquals(1, func.params.size)

        val match = func.body as Match
        assertEquals(2, match.branches.size)

        val branch0 = match.branches[0]
        assertEquals(2, branch0.destructuredBindings.size)

        // $a extracted from $var0 at path [1] (And $a $b) -> atoms[1]
        val bindingA = branch0.destructuredBindings.find { it.originalName == "a" }
        assertNotNull(bindingA)
        assertEquals(0, bindingA.paramIndex)
        assertContentEquals(intArrayOf(1), bindingA.extractionPath)

        // $b extracted from $var0 at path [2] (And $a $b) -> atoms[2]
        val bindingB = branch0.destructuredBindings.find { it.originalName == "b" }
        assertNotNull(bindingB)
        assertEquals(0, bindingB.paramIndex)
        assertContentEquals(intArrayOf(2), bindingB.extractionPath)

        // Second branch: (deduce $x) -> no destructure bindings
        assertTrue(match.branches[1].destructuredBindings.isEmpty())
    }
}