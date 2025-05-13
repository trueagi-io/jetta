package net.singularity.jetta.runtime

object IO {
    @JvmStatic
    fun println(value: Any) {
        kotlin.io.println(value)
    }
}
