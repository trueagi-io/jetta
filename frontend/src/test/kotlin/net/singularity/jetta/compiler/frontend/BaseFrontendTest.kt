package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.rewrite.FunctionRewriter
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.rewrite.CompositeRewriter
import net.singularity.jetta.compiler.frontend.rewrite.LambdaRewriter
import net.singularity.jetta.compiler.parser.antlr.AntlrParserFacadeImpl

abstract class BaseFrontendTest {
    protected fun createParserFacade(): ParserFacade = AntlrParserFacadeImpl()

    protected fun resolve(filename: String, code: String): Pair<ParsedSource, MessageCollector> {
        val messageCollector = MessageCollector()
        val context = Context(messageCollector)
        val parser = createParserFacade()
        val rewriter = CompositeRewriter()
        rewriter.add(FunctionRewriter(messageCollector))
        rewriter.add(LambdaRewriter(messageCollector))
        val parsed = parser.parse(Source(filename, code), messageCollector)
        val result = rewriter.rewrite(parsed)
        context.resolve(result)
        return result to messageCollector
    }
}