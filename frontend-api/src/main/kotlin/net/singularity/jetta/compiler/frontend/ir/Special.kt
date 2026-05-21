package net.singularity.jetta.compiler.frontend.ir

class Special(val value: String, override val position: SourcePosition? = null) : Atom {
    override var type: Atom? = null

    override val id: Int = UniqueAtomIdGenerator.generate()

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Special) return false
        return value == other.value
    }

    override fun hashCode(): Int = value.hashCode()
}