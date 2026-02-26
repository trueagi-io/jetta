
package net.singularity.jetta.compiler.logger

interface Logger {
    val isDebugEnabled: Boolean
    val isInfoEnabled: Boolean
    val isWarnEnabled: Boolean
    val isErrorEnabled: Boolean

    fun debug(msg: () -> String)
    fun info(msg: () -> String)
    fun warn(msg: () -> String)
    fun error(msg: () -> String)

    companion object {
        fun getLogger(clazz: Class<*>): Logger = ConsoleLogger(clazz.simpleName)
    }
}