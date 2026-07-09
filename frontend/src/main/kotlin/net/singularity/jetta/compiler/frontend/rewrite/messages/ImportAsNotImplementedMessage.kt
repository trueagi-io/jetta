package net.singularity.jetta.compiler.frontend.rewrite.messages

import net.singularity.jetta.compiler.frontend.Message
import net.singularity.jetta.compiler.frontend.MessageLevel
import net.singularity.jetta.compiler.frontend.ir.SourcePosition

/**
 * Surfaced when an `import!` form references a target other than `&self` —
 * `&kb`, `&m`, or any other named sub-space. Import-As semantics aren't
 * implemented yet; only Import-All into the importer's own space works.
 */
class ImportAsNotImplementedMessage(
    val targetName: String,
    override val position: SourcePosition?,
) : Message {
    override val level: MessageLevel = MessageLevel.ERROR
}
