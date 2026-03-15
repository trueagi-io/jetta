package net.singularity.jetta.compiler.frontend.ir

data class Run(
    val expression: Expression,
    override val position: SourcePosition? = expression.position
) : Atom {
    override var type: Atom? = GroundedType.UNIT

    override val id: Int = UniqueAtomIdGenerator.generate()

    override fun toString(): String = "!$expression"
}