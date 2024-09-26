package net.singularity.jetta.compiler.frontend.ir

class Lambda(
    override val params: List<Variable>,
    override var arrowType: ArrowType?,
    override val body: Expression,
    override val position: SourcePosition? = null
) : FunctionLike {
    var resolvedClassName: String? = null

    override val returnType: Atom?
        get() = arrowType?.types?.last()

    override var type: Atom? = arrowType

    override val id: Int = UniqueAtomIdGenerator.generate()

    val arity = params.size

    val typedParameters: List<Variable>?
        get() = arrowType?.let { funcType ->
            params
                .zip(funcType.types.dropLast(1))
                .map {
                    it.first.type = it.second
                    it.first
                }
        }

    override fun toString(): String {
        return "Lambda(params=$params, arrowType=$arrowType, body=$body, position=$position)"
    }

    fun copy(body: Expression) = Lambda(params, arrowType, body, position)
}
