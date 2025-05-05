package net.singularity.jetta.compiler.logger

interface Logger {
    fun warn(msg: String)

    fun error(msg: String)

    fun info(msg: String)

    fun debug(msg: String)

    companion object {

        fun getLogger(clazz: Class<*>, logLevel: LogLevel) = object : Logger {
            override fun warn(msg: String) {
                log(LogLevel.WARN, msg)
            }

            override fun error(msg: String) {
                log(LogLevel.ERROR, msg)
            }

            override fun info(msg: String) {
                log(LogLevel.INFO, msg)
            }

            override fun debug(msg: String) {
                log(LogLevel.DEBUG, msg)
            }

            fun log(level: LogLevel, msg: String) {
                if (level >= logLevel) println("[$level] $msg")
            }
        }

    }
}