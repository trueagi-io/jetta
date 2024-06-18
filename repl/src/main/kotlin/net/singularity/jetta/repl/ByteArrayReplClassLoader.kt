package net.singularity.jetta.repl

import net.singularity.jetta.compiler.backend.CompilationResult
import java.net.URLClassLoader

class ByteArrayReplClassLoader :
    URLClassLoader(arrayOf()) {
    private val extraClassDefs = mutableMapOf<String, ByteArray>()

    fun add(compilationResult: CompilationResult) {
        extraClassDefs[compilationResult.className] = compilationResult.bytecode
    }

    @Throws(ClassNotFoundException::class)
    override fun findClass(name: String): Class<*> {
        val classBytes = extraClassDefs.remove(name)
        return if (classBytes != null) {
            defineClass(name, classBytes, 0, classBytes.size)
        } else super.findClass(name)
    }
}