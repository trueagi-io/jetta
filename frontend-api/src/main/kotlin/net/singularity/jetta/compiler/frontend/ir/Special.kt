package net.singularity.jetta.compiler.frontend.ir

class Special(val value: String, override val position: SourcePosition? = null) : Atom {
    override var type: Atom? = null

    override val id: Int = UniqueAtomIdGenerator.generate()

    override fun toString(): String = value
}