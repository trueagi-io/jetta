package net.singularity.jetta.compiler.frontend.resolve

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import java.io.File

fun ParsedSource.getJvmClassName(): String {
    return File(filename).nameWithoutExtension
}

fun Atom.toJvmType(boxing: Boolean = false): String =
    when (this) {
        GroundedType.INT -> if (boxing) "Ljava/lang/Integer;" else "I"
        GroundedType.BOOLEAN -> if (boxing) "Ljava/lang/Boolean;" else "Z"
        GroundedType.DOUBLE -> if (boxing) "Ljava/lang/Double;" else "D"
        is ArrowType -> this.descriptor()
        else -> TODO("Not implemented yet $this")
    }

fun ArrowType.descriptor(): String = "L${getJvmInterfaceName()};"

fun ArrowType.getJvmInterfaceName(): String {
    val arity = this.types.size - 1
    return "net/singularity/jetta/runtime/functions/Function$arity"
}

fun ArrowType.signature(): String {
    val sb = StringBuilder()
    sb.append("L${getJvmInterfaceName()}")
    sb.append('<')
    this.types.forEach {
        sb.append(when (it) {
            GroundedType.INT -> "Ljava/lang/Integer;"
            else -> TODO()
        })
    }
    sb.append(">;")
    return sb.toString()
}

fun ArrowType.getApplyJvmDescriptor(boxing: Boolean): String {
    val sb = StringBuilder()
    sb.append("(")
    types.dropLast(1).map {
        sb.append(it.toJvmType(boxing))
    }
    sb.append(")")
    sb.append(types.last().toJvmType(boxing))
    return sb.toString()
}

fun ArrowType.getApplyJvmPlainDescriptor(): String {
    val sb = StringBuilder()
    sb.append("(")
    types.dropLast(1).map {
        sb.append("Ljava/lang/Object;")
    }
    sb.append(")")
    sb.append("Ljava/lang/Object;")
    return sb.toString()
}

fun Atom.toJvmGenericType(): String =
    when (this) {
        GroundedType.INT -> "I"
        GroundedType.BOOLEAN -> "Z"
        GroundedType.DOUBLE -> "D"
        is ArrowType -> this.signature()
        else -> TODO("Not implemented yet $this")
    }

fun FunctionDefinition.getJvmDescriptor(): String {
    if (name == "main") {
        return "([Ljava/lang/String;)V"
    } else {
        val sb = StringBuilder()
        sb.append("(")
        arrowType!!.types.dropLast(1).map {
            sb.append(it.toJvmType())
        }
        sb.append(")")
        sb.append(arrowType!!.types.last().toJvmType())
        return sb.toString()
    }
}

fun FunctionDefinition.getSignature(): String? {
    arrowType!!.types.find {
        it is ArrowType
    } ?: return null
    val sb = StringBuilder()
    sb.append("(")
    arrowType!!.types.dropLast(1).map {
        sb.append(it.toJvmGenericType())
    }
    sb.append(")")
    sb.append(arrowType!!.types.last().toJvmGenericType())
    return sb.toString()
}


