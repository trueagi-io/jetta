package net.singularity.jetta.compiler.frontend.resolve

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import java.io.File

fun ParsedSource.getJvmClassName(): String {
    return File(filename).nameWithoutExtension
}

fun Atom.toJvmType(boxing: Boolean = false): String =
    when (this) {
        GroundedType.INT -> if (boxing) "Ljava/lang/Integer;" else "I"
        GroundedType.BOOLEAN -> if (boxing) "Ljava/lang/Boolean;" else "Z"
        GroundedType.DOUBLE -> if (boxing) "Ljava/lang/Double;" else "D"
        GroundedType.UNIT -> if (boxing) throw RuntimeException("Should never happen") else "V"
        is ArrowType -> this.descriptor()
        is SeqType -> "Ljava/util/List;"
        else -> TODO("Not implemented yet $this")
    }

fun ArrowType.descriptor(): String = "L${getJvmInterfaceName()};"

fun ArrowType.getJvmInterfaceName(): String {
    val arity = this.types.size - 1
    return "net/singularity/jetta/runtime/functions/Function$arity"
}

fun Atom.signature(): String {
    val sb = StringBuilder()
    when (this) {
        is ArrowType -> {
            sb.append("L${getJvmInterfaceName()}")
            sb.append('<')
            types.forEach {
                sb.append(it.signature())
            }
            sb.append(">;")
        }

        is SeqType -> "Ljava/util/List<${elementType.signature()}>;"
        GroundedType.INT -> "Ljava/lang/Integer;"
        else -> TODO()
    }
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

fun Atom.toJvmGenericType(box: Boolean = false): String =
    when (this) {
        GroundedType.INT -> if (box) "Ljava/lang/Integer;" else "I"
        GroundedType.BOOLEAN -> if (box) "Ljava/lang/Boolean;" else "Z"
        GroundedType.DOUBLE -> if (box) "Ljava/lang/Double;" else "D"
        is ArrowType -> this.signature()
        else -> TODO("Not implemented yet $this")
    }

fun FunctionDefinition.getJvmDescriptor(): String =
    if (name == "main") {
        "([Ljava/lang/String;)V"
    } else {
        val sb = StringBuilder()
        sb.append("(")
        arrowType!!.types.dropLast(1).map {
            sb.append(it.toJvmType())
        }
        sb.append(")")
        if (isMultivalued())
            sb.append("Ljava/util/List;")
        else
            sb.append(arrowType!!.types.last().toJvmType())
        sb.toString()
    }


fun FunctionDefinition.getSignature(): String? {
    if (arrowType!!
            .types
            .find { it.type is SeqType || it.type is ArrowType } == null && !isMultivalued()
    )
        return null

    val sb = StringBuilder()
    sb.append("(")
    arrowType!!.types.dropLast(1).map {
        sb.append(it.toJvmGenericType())
    }
    sb.append(")")
    if (isMultivalued()) {
        val returnType = arrowType!!.types.last().toJvmGenericType(true)
        sb.append("Ljava/util/List<$returnType>;")
    } else {
        val returnType = arrowType!!.types.last().toJvmGenericType(false)
        sb.append(returnType)
    }
    return sb.toString()
}

fun FunctionDefinition.isMultivalued(): Boolean =
    annotations.find { (it as? Symbol)?.name == "multivalued" } != null

fun FunctionDefinition.isExport(): Boolean =
    annotations.find { (it as? Symbol)?.name == "export" } != null


fun JvmMethod.returnType(): Atom =
    toType(descriptor.substring(descriptor.indexOf(')') + 1))

fun JvmMethod.arrowType(): ArrowType = ArrowType(
//    (signature ?: descriptor).parseDescriptor().map(::toType)
    descriptor.parseDescriptor().map(::toType)
)

fun JvmMethod.doesParameterHaveAnyType(index: Int) =
    descriptor.parseDescriptor()[index] == "Ljava/lang/Object;"

private fun toType(jvmType: String): Atom =
    when (jvmType) {
        "I" -> GroundedType.INT
        "D" -> GroundedType.DOUBLE
        "V" -> GroundedType.UNIT
        "Z" -> GroundedType.BOOLEAN
        "Ljava/lang/String;" -> GroundedType.STRING
        "Ljava/lang/Object;" -> GroundedType.ANY
        else -> {
            jvmType.parseArrowType() ?: TODO("type=$jvmType")
        }
    }

private fun String.parseArrowType(): ArrowType? =
    if (startsWith("Lnet/singularity/jetta/runtime/functions/Function")) {
        val bra = indexOf('<')
        val ket = length - 2
        ArrowType(substring(bra + 1, ket).parseDescriptor().map {
            when (it) {
                "Ljava/lang/Integer;" -> GroundedType.INT
                else -> TODO()
            }
        })
    } else null


fun String.parseDescriptor(): List<String> {
    val list = mutableListOf<String>()
    val type = StringBuilder()
    var isObject = false
    var isGeneric = false
    this.forEach {
        if (isObject) {
            type.append(it)
            if (it == '<') isGeneric = true
            if (it == '>') isGeneric = false
            if (it == ';' && !isGeneric) {
                list.add(type.toString())
                type.clear()
                isObject = false
            }
        } else {
            when (it) {
                '(', ')' -> {}
                'I', 'D', 'V', 'Z' -> list.add(it.toString())
                'L' -> {
                    type.append(it)
                    isObject = true
                }

                else -> TODO("jvm type=$it")
            }
        }
    }
    return list
}


