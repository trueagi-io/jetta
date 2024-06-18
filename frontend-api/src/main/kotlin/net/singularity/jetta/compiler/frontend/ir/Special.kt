package net.singularity.jetta.compiler.frontend.ir

data class Special(val value: String, override val position: SourcePosition? = null) : Atom {
    override var type: Atom? = null

    override fun toString(): String = value
}