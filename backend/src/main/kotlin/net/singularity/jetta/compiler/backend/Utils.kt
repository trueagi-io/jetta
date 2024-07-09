package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.toJvmType
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

fun FunctionLike.getParameterIndex(variable: Variable): Int = params.getParameterIndex(variable)

fun List<Variable>.getParameterIndex(variable: Variable): Int {
    var jvmIndex = 0
    forEach {
        if (it.name == variable.name) return jvmIndex
        when (it.type) {
            GroundedType.INT, GroundedType.BOOLEAN -> jvmIndex++
            GroundedType.DOUBLE -> jvmIndex += 2
            else -> TODO("type=" + it.type + " (" + it + ")")
        }
    }
    throw IllegalArgumentException(variable.toString())
}

fun generateLoadVar(mv: MethodVisitor, variable: Variable, function: FunctionLike, isStatic: Boolean) {
    val offset = if (isStatic) 0 else 1
    when (variable.type) {
        GroundedType.INT,
        GroundedType.BOOLEAN -> mv.visitVarInsn(Opcodes.ILOAD, function.getParameterIndex(variable) + offset)

        GroundedType.DOUBLE -> mv.visitVarInsn(Opcodes.DLOAD, function.getParameterIndex(variable) + offset)
        else -> TODO("Not implemented yet " + variable)
    }
}

fun unboxIfNeeded(mv: MethodVisitor, type: GroundedType?) {
    when (type) {
        GroundedType.INT -> {
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Number",
                "intValue",
                "()I",
                false
            )
        }
        null -> { }
        else -> TODO()
    }
}

fun boxIfNeeded(mv: MethodVisitor, type: GroundedType?) {
    when (type) {
        GroundedType.INT -> mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/Integer",
            "valueOf",
            "(I)Ljava/lang/Integer;",
            false
        )
        null -> { }
        else -> TODO()
    }
}

fun Lambda.capturedVariables(): List<Variable> {
    val result = mutableListOf<Variable>()
    fun collect(atom: Atom) {
        when (atom) {
            is Variable -> {
                val found = params.find { it.name == atom.name }
                if (found == null) result.add(atom)
            }
            is Expression -> {
                atom.atoms.forEach {
                    collect(it)
                }
            }
            else -> { }
        }
    }
    body.atoms.forEach {
        collect(it)
    }
    return result
}

fun mkLambdaInitDescriptor(capturedVariables: List<Variable>): String {
    val sb = StringBuilder()
    sb.append('(')
    capturedVariables.forEach {
        sb.append(it.toJvmType())
    }
    sb.append(")V")
    return sb.toString()
}