package net.singularity.jetta.compiler.frontend.ir

data class ArrowType(val types: List<Atom>, override val position: SourcePosition? = null) : Atom {
    constructor(vararg types: Atom) : this(types.asList())

    override var type: Atom? = null

    override val id: Int = UniqueAtomIdGenerator.generate()

    override fun toString(): String = buildString {
        append("(->")
        append(types.joinToString(separator = " "))
        append(")")
    }
}
