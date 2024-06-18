package net.singularity.jetta.compiler.frontend

class MessageCollector {
    private val messages = mutableListOf<Message>()

    fun add(message: Message) {
        messages.add(message)
    }

    fun list(): List<Message> = messages

    fun hasErrors(): Boolean = messages.find { it.level == MessageLevel.ERROR } != null

    fun clear() = messages.clear()
}