package net.singularity.jetta.compiler.frontend.ir

data class SeqType(val elementType: Atom, override val position: SourcePosition? = null): Atom {
    override var type: Atom? = null
    override val id: Int = -1
}