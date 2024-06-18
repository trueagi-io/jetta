package net.singularity.jetta.compiler.frontend.ir

data class Symbol(val name: String, override val position: SourcePosition? = null) : Atom {
    override var type: Atom? = null

    override fun toString(): String = name
}