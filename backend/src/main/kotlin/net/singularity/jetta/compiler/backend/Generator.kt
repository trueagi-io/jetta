package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.resolve.getJvmClassName
import net.singularity.jetta.compiler.frontend.resolve.getJvmDescriptor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

class Generator {

    fun generate(source: ParsedSource): CompilationResult {
        val cw = ClassWriter(COMPUTE_MAXS or COMPUTE_FRAMES)
        val className = source.getJvmClassName()
        cw.visit(
            Constants.JVM_TARGET_VERSION,
            Opcodes.ACC_PUBLIC,
            className,
            null,
            Type.getInternalName(Object::class.java),
            null
        )
        source.code.forEach { node ->
            when (node) {
                is FunctionDefinition -> {
                    val mv = cw.visitMethod(
                        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                        node.name,
                        node.getJvmDescriptor(),
                        null,
                        null
                    )
                    FunctionGenerator(mv, node).generate()
                }
                else -> TODO("Not implemented yet")
            }
        }
        return CompilationResult(className, cw.toByteArray())
    }
}