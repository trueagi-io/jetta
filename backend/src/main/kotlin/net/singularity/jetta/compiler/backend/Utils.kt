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
    return -1
}

fun generateLoadVar(
    mv: MethodVisitor,
    variable: Variable,
    params: List<Variable>,
    isStatic: Boolean,
    className: String?
) {
    fun generateField() {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitFieldInsn(
            Opcodes.GETFIELD,
            className ?: throw IllegalArgumentException(variable.toString()),
            variable.name,
            variable.type!!.toJvmType()
        )
    }

    val offset = if (isStatic) 0 else 1
    when (variable.type) {
        GroundedType.INT,
        GroundedType.BOOLEAN -> {
            val index = params.getParameterIndex(variable)
            if (index < 0)
                generateField()
            else
                mv.visitVarInsn(Opcodes.ILOAD, index + offset)
        }

        GroundedType.DOUBLE -> {
            val index = params.getParameterIndex(variable)
            if (index < 0)
                generateField()
            else
                mv.visitVarInsn(Opcodes.DLOAD, index + offset)
        }

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

        GroundedType.DOUBLE -> {
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Number",
                "doubleValue",
                "()D",
                false
            )
        }


        null -> {}
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

        GroundedType.DOUBLE -> mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/Double",
            "valueOf",
            "(D)Ljava/lang/Double;",
            false
        )

        null -> {}
        else -> TODO()
    }
}

fun Lambda.capturedVariables(): List<Variable> {
    val result = mutableListOf<Variable>()
    fun collect(params: List<Variable>, atom: Atom) {
        when (atom) {
            is Variable -> {
                val found = params.find { it.name == atom.name }
                if (found == null) result.add(atom)
            }

            is Expression -> {
                atom.atoms.forEach {
                    collect(params, it)
                }
            }

            is Lambda -> {
                when (val body = atom.body) {
                    is  Expression -> body.atoms.forEach {
                        collect(params + atom.params, it)
                    }
                    else -> collect(params, atom.body)
                }
            }

            else -> {}
        }
    }
    when (val b = body) {
        is Expression -> b.atoms.forEach {
            collect(params, it)
        }
        else -> collect(params, body)
    }
    return result
}

fun mkLambdaInitDescriptor(capturedVariables: List<Variable>): String {
    val sb = StringBuilder()
    sb.append('(')
    capturedVariables.forEach {
        sb.append(it.type!!.toJvmType())
    }
    sb.append(")V")
    return sb.toString()
}