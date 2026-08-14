package net.singularity.jetta.compiler.frontend.resolve.messages

import net.singularity.jetta.compiler.frontend.Message
import net.singularity.jetta.compiler.frontend.MessageLevel
import net.singularity.jetta.compiler.frontend.ir.SourcePosition

/**
 * A `=` rule defines a name JeTTa grounds natively. Resolution prefers the builtin, so no call
 * site can reach this definition; it stays in the space as data (the reflective path still sees
 * it) but no method is emitted for it.
 */
data class ShadowedByBuiltinMessage(val name: String, override val position: SourcePosition?) : Message {
    override val level: MessageLevel = MessageLevel.WARNING
}
