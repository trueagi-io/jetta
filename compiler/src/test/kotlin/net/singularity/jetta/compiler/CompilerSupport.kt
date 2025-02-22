package net.singularity.jetta.compiler

import net.singularity.jetta.compiler.frontend.Source
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class CompilerSupport {
    private val jettaCompilerPath: File

    private val outputDir: File

    private val sourceDir: File

    private val jettaHome = ".."

    init {
        jettaCompilerPath = jettaHome / "bin" / "jettac"

        if (!jettaCompilerPath.exists())
            throw IOException("$jettaCompilerPath does not exist")

        outputDir = createTempDir()
        outputDir.mkdirs()

        sourceDir = createTempDir()
        sourceDir.mkdirs()
    }

    private fun Source.writeFile() {
        val file = sourceDir / filename
        file.writeText(code)
    }

    fun compile(vararg sources: Source): Map<String, ByteArray> {
        fun collect(root: File, acc: MutableMap<String, ByteArray>) {
            root.listFiles()?.forEach {
                if (it.isDirectory) {
                    collect(it, acc)
                } else if (it.extension == "class") {
                    acc[it.relativeTo(outputDir).path.removeSuffix(".class")] = it.readBytes()
                }
            }
        }

        sources.forEach { source ->
            source.writeFile()
        }

        val result = mutableMapOf<String, ByteArray>()

        val proc = ProcessBuilder(jettaCompilerPath.absolutePath, *sources.map { it.filename }.toTypedArray(), "-d", outputDir.absolutePath)
            .directory(sourceDir)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        proc.waitFor(60, TimeUnit.SECONDS)

        if (proc.exitValue() != 0) throw CompilationErrorException(proc.errorStream.bufferedReader().readText())

        collect(outputDir, result)

        return result
    }

    fun dispose() {
        sourceDir.deleteRecursively()
        outputDir.deleteRecursively()
    }

    fun createTempDir(): File = File(System.getProperty("java.io.tmpdir")) / UUID.randomUUID().toString()

    operator fun File.div(other: String) = File(this.absolutePath + File.separator + other)

    operator fun String.div(other: String) = File(this + File.separator + other)
}