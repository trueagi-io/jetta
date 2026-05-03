package net.singularity.jetta.compiler.frontend.rewrite.messages

import net.singularity.jetta.compiler.frontend.Message
import net.singularity.jetta.compiler.frontend.MessageLevel
import net.singularity.jetta.compiler.frontend.ir.SourcePosition

/**
 * Surfaced when an `import!` chain forms a cycle. [importer] is the file that
 * triggered the cycle detection; [target] is the file it tried to import that
 * is already on the resolution stack.
 */
class CyclicImportMessage(
    val importer: String,
    val target: String,
    override val position: SourcePosition?,
) : Message {
    override val level: MessageLevel = MessageLevel.ERROR
}
