package net.singularity.jetta.repl

import net.singularity.jetta.compiler.frontend.Message

interface MessageRenderer {
    fun render(message: Message): String
}