package net.singularity.jetta.compiler.frontend.resolve

import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.GroundedType

data class JvmMethod(
    val owner: String,
    val name: String,
    val descriptor: String,
    val signature: String? = null
) {
    fun returnType(): Atom =
        toType(descriptor.substring(descriptor.indexOf(')') + 1))

    fun arrowType(): ArrowType = ArrowType(
        (signature?:descriptor).parseDescriptor().map(::toType)
    )

    private fun toType(jvmType: String): Atom =
        when (jvmType) {
            "I" -> GroundedType.INT
            "D" -> GroundedType.DOUBLE
            "V" -> GroundedType.UNIT
            "Z" -> GroundedType.BOOLEAN
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


    private fun String.parseDescriptor(): List<String> {
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
}