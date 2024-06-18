package net.singularity.jetta.compiler.frontend.resolve

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import java.io.File

fun ParsedSource.getJvmClassName(): String {
    return File(filename).nameWithoutExtension
}

fun Atom.toJvmType(): String =
    when (this) {
        GroundedType.INT -> "I"
        GroundedType.BOOLEAN -> "Z"
        GroundedType.DOUBLE -> "D"
        else -> TODO("Not implemented yet")
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
