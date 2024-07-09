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

    fun arrowType(): ArrowType = ArrowType(parseDescriptor().map(::toType))

    private fun toType(jvmType: String): Atom =
        when (jvmType) {
            "I" -> GroundedType.INT
            "D" -> GroundedType.DOUBLE
            "V" -> GroundedType.UNIT
            "Z" -> GroundedType.BOOLEAN
            "Lnet/singularity/jetta/runtime/functions/Function2;" -> ArrowType(listOf(GroundedType.INT, GroundedType.INT, GroundedType.INT))
            else -> TODO("type=$jvmType")
        }

    private fun parseDescriptor(): List<String> {
        val list = mutableListOf<String>()
        val type = StringBuilder()
        var isObject = false
        descriptor.forEach {
            if (isObject) {
                type.append(it)
                if (it == ';') {
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