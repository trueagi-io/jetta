package net.singularity.jetta.compiler.frontend.ir

class Grounded<T>(val value: T, override val position: SourcePosition? = null) : Atom {
    override var type: Atom? =
        when (value) {
            is Int -> GroundedType.INT
            is Long -> GroundedType.LONG
            is Boolean -> GroundedType.BOOLEAN
            is Double -> GroundedType.DOUBLE
            is String -> GroundedType.STRING
            else -> TODO("Not implemented yet $value")
        }

    override val id: Int = UniqueAtomIdGenerator.generate()

    override fun toString(): String = value.toString()
}
