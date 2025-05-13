package net.singularity.jetta.compiler.frontend.resolve

data class JvmMethod(
    val owner: String,
    val name: String,
    val descriptor: String,
    val signature: String? = null
)