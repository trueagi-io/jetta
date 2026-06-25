
package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.Source
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.compiler.parser.antlr.AntlrParserFacadeImpl
import net.singularity.jetta.runtime.space.SpaceDirectorySerializer
import net.singularity.jetta.runtime.space.SpaceImpl
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpaceTest : GeneratorTestBase() {
    @Test
    fun `test match S-expression`() =
        compile(
            "SimpleMatch.metta",
            $$"""
                    ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                    ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                    ; `match` searches for all expressions corresponding to
                    ; the given pattern and produces the output pattern.
                    ; It doesn't search in subexpressions.
                    ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                    ; Some expressions to be matched
                    ((leaf1 leaf2) leaf3)
                    (((leaf0 leaf1) leaf2) leaf3)
                    ; This one contains `((leaf1 leaf2) leaf3)` as a subexpression
                    ; and thus will not be matched
                    (top ((leaf1 leaf2) leaf3))

                    !(match &self (($x leaf2) leaf3) $x)
                    ;  (leaf1 (leaf0 leaf1))
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(1, classes.size)
            val r = classes["SimpleMatch"]!!.getMethod("__main").invoke(null) as List<*>
            println(r)
            assertEquals(2, r.size)
            assertTrue(r.toString().contains("leaf1,"))
            assertTrue(r.toString().contains("(leaf0 leaf1"))
            return@let
        }

    @Test
    fun `test generate match 1`() =
        compile(
            "GenerateMatch1.metta",
            """
                    !(match &self (leaf2) _x)
            """.trimIndent().replace('_', '$')
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(1, classes.size)
            classes["GenerateMatch1"]!!.getMethod("__main").invoke(null)
            return@let
        }

    // Tests to ensure space structure after compilation
    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `space is saved after frontend with simple expressions`() {
        val tempDir = Files.createTempDirectory("jetta-space-test")
        try {
            val messageCollector = MessageCollector()
            val context = Context(messageCollector, space = SpaceImpl())
            registerExternals(context)
            val parser = AntlrParserFacadeImpl()
            val rewriter = CompositeRewriter()
            rewriter.add { FunctionRewriter(messageCollector, context.getSpace()) }
            rewriter.add { LambdaRewriter(messageCollector) }

            val code = $$"""
                (hello world)
                (foo bar)
                (match &self (hello $x) $x)
            """.trimIndent()

            val parsed = parser.parse(Source("SaveSpace.metta", code), messageCollector)
            val rewritten = rewriter.rewrite(parsed)
            context.resolveRecursively(rewritten)

            // Save the space collected by the frontend
            val space = context.getSpace() as SpaceImpl
            SpaceDirectorySerializer.save(space, tempDir, programName = "SaveSpace")

            // Verify files were created
            assertTrue(tempDir.resolve("SaveSpace.jtsf").toFile().exists())
            assertTrue(tempDir.resolve("SaveSpace.manifest.json").toFile().exists())

            // Load and verify content
            val loaded = SpaceDirectorySerializer.load(tempDir, programName = "SaveSpace")
            val results = loaded.match(
                Expression(Symbol("hello"), Variable("x")),
                Variable("x")
            )
            assertEquals(1, results.size)
            assertEquals("world", ((results[0] as BoundAtom).atom as Symbol).name)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `space is saved with multiple expressions and match works after reload`() {
        val tempDir = Files.createTempDirectory("jetta-space-test")
        try {
            val messageCollector = MessageCollector()
            val context = Context(messageCollector, space = SpaceImpl())
            registerExternals(context)
            val parser = AntlrParserFacadeImpl()
            val rewriter = CompositeRewriter()
            rewriter.add { FunctionRewriter(messageCollector, context.getSpace()) }
            rewriter.add { LambdaRewriter(messageCollector) }

            val code = $$"""
                (color red)
                (color green)
                (color blue)
                (shape circle)
                (shape square)
                (match &self (color $x) $x)
            """.trimIndent()

            val parsed = parser.parse(Source("MultiExpr.metta", code), messageCollector)
            val rewritten = rewriter.rewrite(parsed)
            context.resolveRecursively(rewritten)

            val space = context.getSpace() as SpaceImpl
            SpaceDirectorySerializer.save(space, tempDir, programName = "MultiExpr")

            // Load from disk and verify matching
            val loaded = SpaceDirectorySerializer.load(tempDir, programName = "MultiExpr")

            val colorResults = loaded.match(
                Expression(Symbol("color"), Variable("x")),
                Variable("x")
            )
            assertEquals(3, colorResults.size)
            val colorNames = colorResults.map { ((it as BoundAtom).atom as Symbol).name }.toSet()
            assertEquals(setOf("red", "green", "blue"), colorNames)

            val shapeResults = loaded.match(
                Expression(Symbol("shape"), Variable("y")),
                Variable("y")
            )
            assertEquals(2, shapeResults.size)
            val shapeNames = shapeResults.map { ((it as BoundAtom).atom as Symbol).name }.toSet()
            assertEquals(setOf("circle", "square"), shapeNames)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `space is empty when program has no space expressions`() {
        val tempDir = Files.createTempDirectory("jetta-space-test")
        try {
            val messageCollector = MessageCollector()
            val context = Context(messageCollector, space = SpaceImpl())
            registerExternals(context)
            val parser = AntlrParserFacadeImpl()
            val rewriter = CompositeRewriter()
            rewriter.add { FunctionRewriter(messageCollector, context.getSpace()) }
            rewriter.add { LambdaRewriter(messageCollector) }

            val code = $$"""
                (: add (-> Int Int Int))
                (= (add $x $y) (+ $x $y))
            """.trimIndent()

            val parsed = parser.parse(Source("NoSpace.metta", code), messageCollector)
            val rewritten = rewriter.rewrite(parsed)
            context.resolveRecursively(rewritten)

            val space = context.getSpace() as SpaceImpl
            SpaceDirectorySerializer.save(space, tempDir, programName = "NoSpace")

            // Load and verify it's empty
            val loaded = SpaceDirectorySerializer.load(tempDir, programName = "NoSpace")
            val results = loaded.match(
                Expression(Symbol("anything"), Variable("x")),
                Variable("x")
            )
            assertTrue(results.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}