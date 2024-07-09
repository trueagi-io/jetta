package net.singularity.jetta.compiler.frontend.rewrite.messages

import net.singularity.jetta.compiler.frontend.Message
import net.singularity.jetta.compiler.frontend.MessageLevel
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.SourcePosition

class ExpectVariableButFoundMessage(val atom: Atom) : Message {
    override val level: MessageLevel = MessageLevel.ERROR
    override val position: SourcePosition?
        get() = atom.position
}