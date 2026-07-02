package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.toJvmType
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.LocalVariablesSorter

/**
 * A grounded VALUE type — one compiled as a raw JVM primitive or bare String, not as an
 * [net.singularity.jetta.compiler.frontend.ir.Atom] reference. Such a value must be boxed
 * and wrapped in a `Grounded` to live inside quoted/Atom-typed data.
 */
fun GroundedType.isGroundedValue(): Boolean = when (this) {
    GroundedType.INT, GroundedType.LONG, GroundedType.DOUBLE,
    GroundedType.BOOLEAN, GroundedType.STRING -> true
    else -> false
}

fun FunctionLike.getParameterIndex(variable: Variable): Int = params.getParameterIndex(variable)

fun List<Variable>.getParameterIndex(variable: Variable): Int {
    var jvmIndex = 0
    forEach {
        if (it.name == variable.name) return jvmIndex
        // 64-bit primitives (long, double) consume two JVM local slots; everything
        // else (int / boolean / object refs of any flavour) takes one.
        when (it.type) {
            GroundedType.LONG,
            GroundedType.DOUBLE -> jvmIndex += 2

            GroundedType.INT,
            GroundedType.BOOLEAN,
            GroundedType.STRING,
            GroundedType.ANY,
            GroundedType.NOTHING,
            GroundedType.SPACE,
            GroundedType.LIST,
            GroundedType.ATOM,
            GroundedType.EXPRESSION,
            is ArrowType,
            is SeqType -> jvmIndex++

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
    val index = params.getParameterIndex(variable)

    // Variable not found in params and no class to load field from —
    // this is a match-time variable (e.g., nested pattern variable).
    // Generate a runtime Variable object so it can be resolved by the matcher.
    if (index < 0 && className == null) {
        generateNewVariable(mv, variable.name)
        return
    }

    when (variable.type) {
        GroundedType.INT,
        GroundedType.BOOLEAN -> {
            if (index < 0)
                generateField()
            else
                mv.visitVarInsn(Opcodes.ILOAD, index + offset)
        }

        GroundedType.LONG -> {
            if (index < 0)
                generateField()
            else
                mv.visitVarInsn(Opcodes.LLOAD, index + offset)
        }

        GroundedType.DOUBLE -> {
            if (index < 0)
                generateField()
            else
                mv.visitVarInsn(Opcodes.DLOAD, index + offset)
        }

        GroundedType.STRING,
        GroundedType.ANY,
        GroundedType.NOTHING,
        GroundedType.SPACE,
        GroundedType.LIST,
        GroundedType.ATOM,
        GroundedType.EXPRESSION,
        is ArrowType,
        is SeqType -> {
            if (index < 0)
                generateField()
            else
                mv.visitVarInsn(Opcodes.ALOAD, index + offset)
        }

        else -> TODO("Not implemented yet " + variable + " (" + variable.type + ")")
    }
}

/**
 * Given a `Grounded` [net.singularity.jetta.compiler.frontend.ir.Atom] on the stack,
 * unwrap it to the raw boxed value (`Grounded.getValue()`) and unbox to [type]'s JVM
 * primitive. Used when a value that is an Atom at runtime — a destructured pattern
 * variable, or the result of a multivalued/structural-dispatch call — must be consumed
 * as a primitive (arithmetic, comparison, primitive return).
 */
fun unwrapGroundedToPrimitive(mv: MethodVisitor, type: GroundedType) {
    mv.visitTypeInsn(Opcodes.CHECKCAST, "net/singularity/jetta/compiler/frontend/ir/Grounded")
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "net/singularity/jetta/compiler/frontend/ir/Grounded",
        "getValue",
        "()Ljava/lang/Object;",
        false
    )
    unboxIfNeeded(mv, type)
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

        GroundedType.LONG -> {
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Number",
                "longValue",
                "()J",
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

        GroundedType.BOOLEAN -> {
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean")
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Boolean",
                "booleanValue",
                "()Z",
                false
            )
        }

        // Reference types — already objects, no unbox needed.
        GroundedType.STRING,
        GroundedType.ANY,
        GroundedType.NOTHING,
        GroundedType.SPACE,
        GroundedType.LIST,
        GroundedType.ATOM,
        GroundedType.EXPRESSION,
        null -> {
        }

        else -> TODO("Not implemented yet $type")
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

        GroundedType.LONG -> mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/Long",
            "valueOf",
            "(J)Ljava/lang/Long;",
            false
        )

        GroundedType.DOUBLE -> mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/Double",
            "valueOf",
            "(D)Ljava/lang/Double;",
            false
        )

        GroundedType.BOOLEAN -> mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/Boolean",
            "valueOf",
            "(Z)Ljava/lang/Boolean;",
            false
        )

        GroundedType.STRING,
        GroundedType.ANY,
        GroundedType.NOTHING,
        GroundedType.SPACE,
        GroundedType.LIST,
        GroundedType.ATOM,
        GroundedType.EXPRESSION,
        null -> {
        }

        else -> TODO("Not implemented yet $type")
    }
}

fun Lambda.capturedVariables(): List<Variable> {
    val result = mutableListOf<Variable>()
    val seen = mutableSetOf<String>()
    fun collect(params: List<Variable>, atom: Atom) {
        when (atom) {
            is Variable -> {
                val found = params.find { it.name == atom.name }
                if (found == null && seen.add(atom.name)) result.add(atom)
            }

            is Expression -> {
                atom.atoms.forEach {
                    collect(params, it)
                }
            }

            is Lambda -> {
                when (val body = atom.body) {
                    is Expression -> body.atoms.forEach {
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

fun generateLoadInt(mv: LocalVariablesSorter, value: Int) {
    when (value) {
        0 -> mv.visitInsn(Opcodes.ICONST_0)
        1 -> mv.visitInsn(Opcodes.ICONST_1)
        2 -> mv.visitInsn(Opcodes.ICONST_2)
        3 -> mv.visitInsn(Opcodes.ICONST_3)
        4 -> mv.visitInsn(Opcodes.ICONST_4)
        5 -> mv.visitInsn(Opcodes.ICONST_5)
        // BIPUSH/SIPUSH take a SIGNED byte/short operand, so they must only be used
        // within their range — else the operand wraps (144 → -112). Widen past them to
        // an LDC of the int constant. Covers negatives too (e.g. -1 → BIPUSH).
        in Byte.MIN_VALUE.toInt()..Byte.MAX_VALUE.toInt() -> mv.visitIntInsn(Opcodes.BIPUSH, value)
        in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt() -> mv.visitIntInsn(Opcodes.SIPUSH, value)
        else -> mv.visitLdcInsn(value)
    }
}

/**
 * Emits bytecode to create a new runtime [Variable].
 */
fun generateNewVariable(mv: MethodVisitor, name: String) {
    mv.visitTypeInsn(Opcodes.NEW, "net/singularity/jetta/compiler/frontend/ir/Variable")
    mv.visitInsn(Opcodes.DUP)
    mv.visitLdcInsn(name)
    mv.visitInsn(Opcodes.ACONST_NULL)
    mv.visitInsn(Opcodes.ACONST_NULL)
    mv.visitIntInsn(Opcodes.BIPUSH, 6)
    mv.visitInsn(Opcodes.ACONST_NULL)
    mv.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "net/singularity/jetta/compiler/frontend/ir/Variable",
        "<init>",
        "(Ljava/lang/String;Lnet/singularity/jetta/compiler/frontend/ir/Atom;Lnet/singularity/jetta/compiler/frontend/ir/SourcePosition;ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
        false
    )
}