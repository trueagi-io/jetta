package net.singularity.jetta.compiler.backend.utils

fun Map<String, ByteArray>.toClasses(): Map<String, Class<*>> {
    val loader = ByteArrayClassLoader(this)
    return mapValues { loader.loadClass(it.key) }
}
