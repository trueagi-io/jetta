package net.singularity.jetta.compiler.frontend.resolve.messages

import net.singularity.jetta.compiler.frontend.Message
import net.singularity.jetta.compiler.frontend.MessageLevel
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.SourcePosition

data class IncompatibleTypesMessage(val requiredType: Atom, val foundType: Atom, override val position: SourcePosition?) : Message {
    override val level: MessageLevel = MessageLevel.ERROR
}
