package net.singularity.jetta.compiler.frontend.resolve

import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.GroundedType

data class JvmMethod(val owner: String, val name: String, val descriptor: String) {
    fun returnType(): Atom =
        toType(descriptor.substring(descriptor.indexOf(')') + 1))

    fun arrowType(): ArrowType = ArrowType(parseDescriptor().map(::toType))

    private fun toType(jvmType: String): Atom =
        when (jvmType) {
            "I" -> GroundedType.INT
            "D" -> GroundedType.DOUBLE
            "V" -> GroundedType.UNIT
            "Z" -> GroundedType.BOOLEAN
            else -> TODO("type=$jvmType")
        }

    private fun parseDescriptor(): List<String> {
        val list = mutableListOf<String>()
        descriptor.forEach {
            when (it) {
                '(', ')' -> {}
                'I', 'D', 'V', 'Z' -> list.add(it.toString())
                else -> TODO("jvm type=$it")
            }
        }
        return list
    }
}