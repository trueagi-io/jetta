package net.singularity.jetta.compiler.frontend.rewrite.messages

import net.singularity.jetta.compiler.frontend.Message
import net.singularity.jetta.compiler.frontend.MessageLevel
import net.singularity.jetta.compiler.frontend.ir.SourcePosition

/**
 * Surfaced when an `import!` form names a module with characters outside
 * `[A-Za-z0-9_-]+`. v1 supports only sibling-relative names; hierarchical
 * paths and traversal segments are explicitly rejected.
 */
class InvalidModuleNameMessage(
    val moduleName: String,
    override val position: SourcePosition?,
) : Message {
    override val level: MessageLevel = MessageLevel.ERROR
}
