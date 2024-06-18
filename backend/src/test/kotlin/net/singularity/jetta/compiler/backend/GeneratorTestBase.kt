package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.ByteArrayClassLoader
import java.io.File

open class GeneratorTestBase {
    protected fun writeResult(result: CompilationResult) {
        val file = File("/tmp/metta/${result.className}.class")
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        file.writeBytes(result.bytecode)
    }

    private fun Map<String, ByteArray>.toClasses(): Map<String, Class<*>> {
        val loader = ByteArrayClassLoader(this)
        return mapValues { loader.loadClass(it.key) }
    }

    private fun List<CompilationResult>.toMap() = this.associate { it.className to it.bytecode }

    protected fun CompilationResult.getClass(): Class<*> = listOf(this).toMap().toClasses()[this.className]!!

}