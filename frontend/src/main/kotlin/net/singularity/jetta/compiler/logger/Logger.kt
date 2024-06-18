package net.singularity.jetta.compiler.logger

interface Logger {
    fun warn(msg: String)

    fun error(msg: String)

    fun info(msg: String)

    fun debug(msg: String)

    companion object {
        private val logLevel = Level.DEBUG
        private enum class Level {
            DEBUG, WARN, ERROR, INFO
        }

        fun getLogger(clazz: Class<*>) = object : Logger {
            override fun warn(msg: String) {
                log(Level.WARN, msg)
            }

            override fun error(msg: String) {
                log(Level.ERROR, msg)
            }

            override fun info(msg: String) {
                log(Level.INFO, msg)
            }

            override fun debug(msg: String) {
                log(Level.DEBUG, msg)
            }

            fun log(level: Level, msg: String) {
                if (level >= logLevel) println("[$level] $msg")
            }
        }

    }
}