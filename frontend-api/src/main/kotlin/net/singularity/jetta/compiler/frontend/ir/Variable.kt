package net.singularity.jetta.compiler.frontend.ir

data class Variable(
    val name: String,
    override var type: Atom? = null,
    override val position: SourcePosition? = null
) : Atom {
    override fun toString(): String = buildString {
        append("$$name")
        if (type != null) append(":$type")
    }
}