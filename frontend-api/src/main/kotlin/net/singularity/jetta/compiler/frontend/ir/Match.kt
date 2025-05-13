package net.singularity.jetta.compiler.frontend.ir

data class Match(
    val branches: List<MatchBranch>,
    val returnType: Atom?,
    override val position: SourcePosition? = null
) : Atom {
    override var type: Atom? = null
    override val id: Int = UniqueAtomIdGenerator.generate()
}