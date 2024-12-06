package net.singularity.jetta.compiler.frontend.resolve.messages

import net.singularity.jetta.compiler.frontend.Message
import net.singularity.jetta.compiler.frontend.MessageLevel
import net.singularity.jetta.compiler.frontend.ir.SourcePosition

data class UndefinedVariableMessage(val name: String, override val position: SourcePosition?) : Message {
    override val level: MessageLevel = MessageLevel.ERROR
}