package net.singularity.jetta.compiler.frontend.ir

data class FunctionDefinition(
    val name: String,
    override val params: List<Variable>,
    override var arrowType: ArrowType?,
    override val body: Atom,
    val annotations: MutableList<Atom> = mutableListOf(),
    override val position: SourcePosition? = null
) : FunctionLike {
    override val returnType: Atom?
        get() = arrowType?.types?.last()

    override var type: Atom? = arrowType

    override val id: Int = UniqueAtomIdGenerator.generate()

    val typedParameters: List<Variable>?
        get() = arrowType?.let { funcType ->
            params
                .zip(funcType.types.dropLast(1))
                .map {
                    it.first.type = it.second
                    it.first
                }
        }
}
