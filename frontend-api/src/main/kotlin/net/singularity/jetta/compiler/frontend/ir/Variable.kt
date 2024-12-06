package net.singularity.jetta.compiler.frontend.ir

class Variable(
    val name: String,
    override var type: Atom? = null,
    override val position: SourcePosition? = null
) : Atom {
    override val id: Int = UniqueAtomIdGenerator.generate()

    var scope: Expression? = null

    override fun toString(): String = buildString {
        append("$$name")
        if (type != null) append(":$type")
    }
}