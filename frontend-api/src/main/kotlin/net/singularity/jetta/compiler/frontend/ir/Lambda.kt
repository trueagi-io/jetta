package net.singularity.jetta.compiler.frontend.ir

data class Lambda(
    override val params: List<Variable>,
    override var arrowType: ArrowType?,
    override val body: Expression,
    override val position: SourcePosition? = null
) : FunctionLike {
    var resolvedClassName: String? = null

    override val returnType: Atom?
        get() = arrowType?.types?.last()

    override var type: Atom? = arrowType

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
}
