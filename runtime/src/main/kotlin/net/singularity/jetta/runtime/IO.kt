package net.singularity.jetta.runtime

object IO {
    @JvmStatic
    fun println(value: Int) {
        kotlin.io.println(value)
    }
}
