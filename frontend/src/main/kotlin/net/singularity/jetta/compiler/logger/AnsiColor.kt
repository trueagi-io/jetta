package net.singularity.jetta.compiler.logger


internal object AnsiColor {
    const val RESET = "\u001B[0m"
    const val RED = "\u001B[31m"
    const val YELLOW = "\u001B[33m"
    const val CYAN = "\u001B[36m"
    const val GRAY = "\u001B[90m"

    fun forLevel(level: LogLevel): String = when (level) {
        LogLevel.DEBUG -> GRAY
        LogLevel.INFO -> CYAN
        LogLevel.WARN -> YELLOW
        LogLevel.ERROR -> RED
    }
}