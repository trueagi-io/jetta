package net.singularity.jetta.compiler.frontend.ir

class Grounded<T>(val value: T, override val position: SourcePosition? = null) : Atom {
    override var type: Atom? =
        when (value) {
            is Int -> GroundedType.INT
            is Long -> GroundedType.LONG
            is Boolean -> GroundedType.BOOLEAN
            is Double -> GroundedType.DOUBLE
            is String -> GroundedType.STRING
            // Any other object is an opaque grounded value (e.g. a runtime state cell) — a
            // plain reference with no primitive unboxing. This is the whole point of a
            // "grounded" atom: wrap a foreign object and carry it through as an Atom.
            else -> GroundedType.ATOM
        }

    override val id: Int = UniqueAtomIdGenerator.generate()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Grounded<*>) return false
        return value == other.value
    }

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value.toString()
}
