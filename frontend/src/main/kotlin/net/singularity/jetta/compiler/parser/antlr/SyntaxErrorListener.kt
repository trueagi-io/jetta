package net.singularity.jetta.compiler.parser.antlr

import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ir.Position
import net.singularity.jetta.compiler.frontend.ir.SourcePosition
import net.singularity.jetta.compiler.parser.messages.ParseErrorMessage
import org.antlr.v4.kotlinruntime.BaseErrorListener
import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.Recognizer

class SyntaxErrorListener(private val filename: String, private val messageCollector: MessageCollector) :
    BaseErrorListener() {
    override fun syntaxError(
        recognizer: Recognizer<*, *>,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String,
        e: RecognitionException?
    ) {
        val position = Position(line, charPositionInLine + 1)
        messageCollector.add(ParseErrorMessage(msg, SourcePosition(filename, position, position)))
    }
}