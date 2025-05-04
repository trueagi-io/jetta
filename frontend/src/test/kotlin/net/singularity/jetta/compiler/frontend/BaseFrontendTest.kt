package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.compiler.parser.antlr.AntlrParserFacadeImpl
import kotlin.test.assertEquals

abstract class BaseFrontendTest {
    protected fun createParserFacade(): ParserFacade = AntlrParserFacadeImpl()

    protected fun resolve(filename: String, code: String,
                          internalMap: JvmMethod? = null,
                          internalFlatMap: JvmMethod? = null, init: (Context) -> Unit = {}): Pair<ParsedSource, MessageCollector> {
        val messageCollector = MessageCollector()
        val context = Context(messageCollector, internalMap, internalFlatMap)
        init(context)
        val parser = createParserFacade()
        val rewriter = CompositeRewriter()
        rewriter.add { FunctionRewriter(messageCollector) }
        rewriter.add { LambdaRewriter(messageCollector) }
        val parsed = parser.parse(Source(filename, code), messageCollector)
        val result = rewriter.rewrite(parsed)
        return context.resolveRecursively(result) to messageCollector
    }

    protected fun resolveMultiple(vararg sources: Source, internalMap: JvmMethod? = null,
                                  internalFlatMap: JvmMethod? = null): Pair<List<ParsedSource>, MessageCollector> {
        val messageCollector = MessageCollector()
        val context = Context(messageCollector, internalMap, internalFlatMap)
        val parser = createParserFacade()
        val rewriter = CompositeRewriter()
        rewriter.add { FunctionRewriter(messageCollector) }
        rewriter.add { LambdaRewriter(messageCollector) }
        val parsed = sources.map {
            val parsed = parser.parse(it, messageCollector)
            val result = rewriter.rewrite(parsed)
            context.addExternalFunctions(result)
            result
        }
        return parsed.map { context.resolveRecursively(it) } to messageCollector
    }

    fun justParse(code: String): ParsedSource {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val result = parser.parse(
            Source(
                "Hello.metta",
                code.trimIndent().replace('_', '$')
            ),
            messageCollector
        )
        assertEquals(0, messageCollector.list().size)
        return result
    }
}