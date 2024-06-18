package net.singularity.jetta.compiler.frontend.ir

data class Expression(
    val atoms: List<Atom>,
    override var type: Atom? = null,
    var resolved: ResolvedSymbol? = null,
    override val position: SourcePosition? = null
) : Atom {

    constructor(vararg atoms: Atom, type: Atom? = null, resolved: ResolvedSymbol? = null) : this(
        atoms.asList(),
        type,
        resolved
    )

    fun children(): List<Atom> {
        return atoms
    }

    override fun toString(): String = buildString {
        append("(")
        append(atoms.joinToString(separator = " "))
        append(")")
        if (type != null) append(":$type")
    }
}