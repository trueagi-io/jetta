package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.SourcePosition

interface Message {
    val level: MessageLevel
    val position: SourcePosition?
}