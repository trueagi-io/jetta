package net.singularity.jetta.compiler.logger

import java.io.PrintStream

object LogConfig {
    @Volatile var level: LogLevel = LogLevel.INFO
    @Volatile var colorMode: ColorMode = ColorMode.AUTO
    @Volatile var output: PrintStream = System.err

    enum class ColorMode { AUTO, ON, OFF }

    fun colorsEnabled(): Boolean = when (colorMode) {
        ColorMode.ON -> true
        ColorMode.OFF -> false
        ColorMode.AUTO -> System.console() != null
                && System.getenv("NO_COLOR") == null
    }
}