package net.singularity.jetta.compiler.logger

internal class ConsoleLogger(private val tag: String) : Logger {
    override val isDebugEnabled: Boolean get() = LogConfig.level <= LogLevel.DEBUG
    override val isInfoEnabled: Boolean get() = LogConfig.level <= LogLevel.INFO
    override val isWarnEnabled: Boolean get() = LogConfig.level <= LogLevel.WARN
    override val isErrorEnabled: Boolean get() = LogConfig.level <= LogLevel.ERROR
    override val isTraceEnabled: Boolean get() = LogConfig.level <= LogLevel.TRACE

    override fun debug(msg: () -> String) { if (isDebugEnabled) emit(LogLevel.DEBUG, msg()) }
    override fun info(msg: () -> String)  { if (isInfoEnabled)  emit(LogLevel.INFO, msg()) }
    override fun warn(msg: () -> String)  { if (isWarnEnabled)  emit(LogLevel.WARN, msg()) }
    override fun error(msg: () -> String) { if (isErrorEnabled) emit(LogLevel.ERROR, msg()) }
    override fun trace(msg: () -> String) { if (isTraceEnabled) emit(LogLevel.TRACE, msg()) }

    private fun emit(level: LogLevel, msg: String) {
        val out = LogConfig.output
        val elapsed = LogConfig.elapsedMs()
        if (LogConfig.colorsEnabled()) {
            val color = AnsiColor.forLevel(level)
            out.println("${AnsiColor.GRAY}${elapsed}ms${AnsiColor.RESET} ${color}[${level}]${AnsiColor.RESET} $tag: $msg")
        } else {
            out.println("${elapsed}ms [${level}] $tag: $msg")
        }
    }
}