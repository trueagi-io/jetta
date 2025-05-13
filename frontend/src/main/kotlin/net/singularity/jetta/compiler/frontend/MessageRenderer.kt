package net.singularity.jetta.compiler.frontend

interface MessageRenderer {
    fun render(message: Message): String
}