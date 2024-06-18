package net.singularity.jetta.compiler.parser.antlr

import net.singularity.compiler.frontend.parser.antlr.generated.JettaLexer
import net.singularity.compiler.frontend.parser.antlr.generated.JettaParser
import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ParserFacade
import net.singularity.jetta.compiler.frontend.Source
import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.tree.ParseTree

class AntlrParserFacadeImpl : ParserFacade {
    override fun parse(source: Source, messageCollector: MessageCollector): ParsedSource {
        val inputStream: CharStream = CharStreams.fromString(source.code)
        val lex = JettaLexer(inputStream)
        val stream = CommonTokenStream(lex)
        val parser = JettaParser(stream)
        val tree: ParseTree = parser.program()
        val visitor = JettaVisitorImpl(source.filename)
        visitor.visit(tree)
        return visitor.getParsedSource() ?: TODO()
    }
}