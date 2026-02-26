package net.singularity.jetta.compiler.logger

internal class ConsoleLogger(private val tag: String) : Logger {

    override val isDebugEnabled: Boolean get() = LogConfig.level <= LogLevel.DEBUG
    override val isInfoEnabled: Boolean get() = LogConfig.level <= LogLevel.INFO
    override val isWarnEnabled: Boolean get() = LogConfig.level <= LogLevel.WARN
    override val isErrorEnabled: Boolean get() = LogConfig.level <= LogLevel.ERROR

    override fun debug(msg: () -> String) { if (isDebugEnabled) emit(LogLevel.DEBUG, msg()) }
    override fun info(msg: () -> String)  { if (isInfoEnabled)  emit(LogLevel.INFO, msg()) }
    override fun warn(msg: () -> String)  { if (isWarnEnabled)  emit(LogLevel.WARN, msg()) }
    override fun error(msg: () -> String) { if (isErrorEnabled) emit(LogLevel.ERROR, msg()) }

    private fun emit(level: LogLevel, msg: String) {
        val out = LogConfig.output
        if (LogConfig.colorsEnabled()) {
            val color = AnsiColor.forLevel(level)
            out.println("${color}[${level}]${AnsiColor.RESET} $tag: $msg")
        } else {
            out.println("[${level}] $tag: $msg")
        }
    }
}