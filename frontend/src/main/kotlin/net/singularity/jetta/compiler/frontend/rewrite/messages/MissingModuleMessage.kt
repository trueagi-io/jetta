package net.singularity.jetta.compiler.frontend.rewrite.messages

import net.singularity.jetta.compiler.frontend.Message
import net.singularity.jetta.compiler.frontend.MessageLevel
import net.singularity.jetta.compiler.frontend.ir.SourcePosition

/**
 * Surfaced when an `import!` form references a module that cannot be located.
 * [resolvedPath] is the path the resolver attempted to read.
 */
class MissingModuleMessage(
    val moduleName: String,
    val resolvedPath: String,
    override val position: SourcePosition?,
) : Message {
    override val level: MessageLevel = MessageLevel.ERROR
}
