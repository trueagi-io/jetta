package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

class LambdaGenerator(private val className: String, private val lambda: Lambda) {
    private val capturedVariables = lambda.capturedVariables()

    fun generate(): CompilationResult {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Constants.JVM_TARGET_VERSION,
            Opcodes.ACC_PUBLIC,
            className,
            lambda.arrowType!!.signature(),
            Type.getInternalName(Object::class.java),
            arrayOf(lambda.arrowType!!.getJvmInterfaceName())
        )
        val init = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "<init>",
            mkLambdaInitDescriptor(capturedVariables),
            null,
            null
        )
        generateFields(cw)
        generateInit(init)
        val genericApply = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "apply",
            lambda.arrowType!!.getApplyJvmPlainDescriptor(),
            null,
            null
        )
        generateGenericApply(genericApply)
        val apply = cw.visitMethod(
            Opcodes.ACC_PRIVATE,
            "apply",
            lambda.arrowType!!.getApplyJvmDescriptor(false),
            null,
            null
        )
        generateApply(apply)
        return CompilationResult(className, cw.toByteArray())
    }

    private fun generateFields(cw: ClassWriter) {
        capturedVariables.forEach {
            cw.visitField(
                Opcodes.ACC_PRIVATE,
                it.name,
                it.type!!.toJvmType(),
                null,
                null
            )
        }
    }

    private fun generateInit(mv: MethodVisitor) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/Object",
            "<init>",
            "()V",
            false
        )
        capturedVariables.forEach {
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            generateLoadVar(mv, it, capturedVariables, false, null)
            mv.visitFieldInsn(Opcodes.PUTFIELD, className, it.name, it.type!!.toJvmType())
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
    }

    private fun generateApply(mv: MethodVisitor) {
        val generator = FunctionGenerator(mv, lambda, false, className)
        generator.generate()
    }

    private fun generateGenericApply(mv: MethodVisitor) {
        mv.visitVarInsn(Opcodes.ALOAD, 0) // this
        lambda.params.forEachIndexed { index, variable ->
            mv.visitVarInsn(Opcodes.ALOAD, index + 1)
            unboxIfNeeded(mv, variable.type as? GroundedType)
        }
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            className,
            "apply",
            lambda.arrowType!!.getApplyJvmDescriptor(false),
            false
        )
        boxIfNeeded(mv, lambda.returnType as? GroundedType)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(lambda.params.size + 1, lambda.params.size + 1)
    }
}