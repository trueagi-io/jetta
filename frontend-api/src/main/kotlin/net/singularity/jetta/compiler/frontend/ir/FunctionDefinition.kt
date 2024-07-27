package net.singularity.jetta.compiler.frontend.ir

class FunctionDefinition(
    val name: String,
    override val params: List<Variable>,
    override var arrowType: ArrowType?,
    override val body: Expression,
    override val position: SourcePosition? = null
) : FunctionLike {
    override val returnType: Atom?
        get() = arrowType?.types?.last()

    override var type: Atom? = arrowType

    val typedParameters: List<Variable>?
        get() = arrowType?.let { funcType ->
            params
                .zip(funcType.types.dropLast(1))
                .map {
                    it.first.type = it.second
                    it.first
                }
        }

    fun copy(body: Expression) = FunctionDefinition(name, params, arrowType, body, position)
}
