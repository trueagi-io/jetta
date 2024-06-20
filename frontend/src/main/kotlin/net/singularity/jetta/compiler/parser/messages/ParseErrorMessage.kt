package net.singularity.jetta.compiler.parser.messages

import net.singularity.jetta.compiler.frontend.Message
import net.singularity.jetta.compiler.frontend.MessageLevel
import net.singularity.jetta.compiler.frontend.ir.SourcePosition


data class ParseErrorMessage(val message: String, override val position: SourcePosition?) : Message {
    override val level: MessageLevel = MessageLevel.ERROR
}
