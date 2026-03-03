package net.singularity.jetta.compiler.frontend.ir

class Symbol(val name: String, override val position: SourcePosition? = null) : Atom {
    override var type: Atom? = null

    override val id: Int = UniqueAtomIdGenerator.generate()

    override fun toString(): String = name

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Symbol) return false
        return name == other.name
    }

    override fun hashCode(): Int = name.hashCode()
}