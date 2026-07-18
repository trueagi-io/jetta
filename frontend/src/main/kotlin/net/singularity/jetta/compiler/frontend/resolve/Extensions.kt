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
        GroundedType.LONG -> if (boxing) "Ljava/lang/Long;" else "J"
        GroundedType.BOOLEAN -> if (boxing) "Ljava/lang/Boolean;" else "Z"
        GroundedType.DOUBLE -> if (boxing) "Ljava/lang/Double;" else "D"
        GroundedType.UNIT -> if (boxing) throw RuntimeException("Should never happen") else "V"
        GroundedType.STRING -> "Ljava/lang/String;"
        GroundedType.ANY, GroundedType.NOTHING -> "Ljava/lang/Object;"
        GroundedType.SPACE -> "Lnet/singularity/jetta/runtime/space/Space;"
        GroundedType.LIST -> "Ljava/util/List;"
        GroundedType.ATOM -> "Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
        GroundedType.EXPRESSION -> "Lnet/singularity/jetta/compiler/frontend/ir/Expression;"
        is ArrowType -> this.descriptor()
        is SeqType -> "Ljava/util/List;"
        else -> TODO("Not implemented yet $this")
    }

fun ArrowType.descriptor(): String = "L${getJvmInterfaceName()};"

fun ArrowType.getJvmInterfaceName(): String =
    "net/singularity/jetta/runtime/functions/JettaFunction"


fun Atom.signature(): String {
    val sb = StringBuilder()
    when (this) {
        is ArrowType -> {
            sb.append("L${getJvmInterfaceName()};")
        }

        is SeqType -> sb.append("Ljava/util/List<${elementType.signature()}>;")
        // Generic-signature uses are always boxed.
        GroundedType.INT -> sb.append("Ljava/lang/Integer;")
        GroundedType.LONG -> sb.append("Ljava/lang/Long;")
        GroundedType.BOOLEAN -> sb.append("Ljava/lang/Boolean;")
        GroundedType.DOUBLE -> sb.append("Ljava/lang/Double;")
        GroundedType.UNIT -> sb.append("Ljava/lang/Void;")
        GroundedType.STRING -> sb.append("Ljava/lang/String;")
        GroundedType.ANY, GroundedType.NOTHING -> sb.append("Ljava/lang/Object;")
        GroundedType.SPACE -> sb.append("Lnet/singularity/jetta/runtime/space/Space;")
        GroundedType.LIST -> sb.append("Ljava/util/List;")
        GroundedType.ATOM -> sb.append("Lnet/singularity/jetta/compiler/frontend/ir/Atom;")
        GroundedType.EXPRESSION -> sb.append("Lnet/singularity/jetta/compiler/frontend/ir/Expression;")
        else -> TODO("Not implemented yet $this")
    }
    return sb.toString()
}

/**
 * SAM descriptor of [JettaFunction]: `([Ljava/lang/Object;)Ljava/lang/Object;`.
 * Single shape for all arities — args packed into an `Object[]`, result boxed.
 */
fun ArrowType.getApplyJvmPlainDescriptor(): String =
    "([Ljava/lang/Object;)Ljava/lang/Object;"

fun Atom.toJvmGenericType(box: Boolean = false): String =
    when (this) {
        GroundedType.INT -> if (box) "Ljava/lang/Integer;" else "I"
        GroundedType.LONG -> if (box) "Ljava/lang/Long;" else "J"
        GroundedType.BOOLEAN -> if (box) "Ljava/lang/Boolean;" else "Z"
        GroundedType.DOUBLE -> if (box) "Ljava/lang/Double;" else "D"
        GroundedType.UNIT -> if (box) "Ljava/lang/Void;" else "V"
        GroundedType.STRING -> "Ljava/lang/String;"
        GroundedType.ANY, GroundedType.NOTHING -> "Ljava/lang/Object;"
        GroundedType.SPACE -> "Lnet/singularity/jetta/runtime/space/Space;"
        GroundedType.LIST -> "Ljava/util/List;"
        GroundedType.ATOM -> "Lnet/singularity/jetta/compiler/frontend/ir/Atom;"
        GroundedType.EXPRESSION -> "Lnet/singularity/jetta/compiler/frontend/ir/Expression;"
        is ArrowType -> this.signature()
        is SeqType -> "Ljava/util/List<${elementType.signature()}>;"
        else -> TODO("Not implemented yet $this")
    }

fun FunctionDefinition.getJvmDescriptor(): String =
    if (name == "main") {
        "([Ljava/lang/String;)V"
    } else {
        val sb = StringBuilder()
        sb.append("(")
        if (arrowType == null) {
            println("STOP: " + this)
        }
        arrowType!!.types.dropLast(1).forEach {
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

fun JvmMethod.isParameterAtomType(index: Int) =
    descriptor.parseDescriptor()[index] == "Lnet/singularity/jetta/compiler/frontend/ir/Atom;"

fun JvmMethod.isParameterBooleanType(index: Int) =
    descriptor.parseDescriptor()[index] == "Z"

private fun toType(jvmType: String): Atom =
    when (jvmType) {
        "I" -> GroundedType.INT
        "J" -> GroundedType.LONG
        "D" -> GroundedType.DOUBLE
        "V" -> GroundedType.UNIT
        "Z" -> GroundedType.BOOLEAN
        "Ljava/lang/String;" -> GroundedType.STRING
        "Ljava/lang/Object;" -> GroundedType.ANY
        "Lnet/singularity/jetta/runtime/space/Space;" -> GroundedType.SPACE
        "Lnet/singularity/jetta/compiler/frontend/ir/Expression;" -> GroundedType.EXPRESSION
        "Lnet/singularity/jetta/compiler/frontend/ir/Atom;" -> GroundedType.ATOM
        "Ljava/util/List;" -> GroundedType.LIST
        else -> {
            jvmType.parseArrowType() ?: TODO("type=$jvmType")
        }
    }

private fun String.parseArrowType(): ArrowType? =
    if (startsWith("Lnet/singularity/jetta/runtime/functions/JettaFunction")) {
        // Single SAM interface carries no per-arity / per-type info in its bytecode name.
        // Callers fall back to descriptor-level inspection if they need arity.
        ArrowType(emptyList())
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
                'I', 'D', 'V', 'Z', 'J' -> list.add(it.toString())
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
