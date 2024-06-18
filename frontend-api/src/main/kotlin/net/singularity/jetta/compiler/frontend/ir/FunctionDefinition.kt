package net.singularity.jetta.compiler.frontend.ir

data class FunctionDefinition(
    val name: String,
    val params: List<Variable>,
    var arrowType: ArrowType?,
    val body: Expression,
    override val position: SourcePosition? = null
) : Atom {
    val returnType: Atom?
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
}
