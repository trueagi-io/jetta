package net.singularity.jetta.compiler.frontend.ir

class FunctionDefinition(
    val name: String,
    override val params: List<Variable>,
    override var arrowType: ArrowType?,
    override val body: Expression,
    val annotations: List<Atom> = listOf(),
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

    fun copy(body: Expression) = FunctionDefinition(name, params, arrowType, body, annotations, position)

    override fun toString(): String {
        return "FunctionDefinition(name='$name', params=$params, arrowType=$arrowType, body=$body, annotations=$annotations, position=$position, type=$type)"
    }
}
