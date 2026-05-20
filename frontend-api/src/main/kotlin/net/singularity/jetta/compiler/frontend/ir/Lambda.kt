package net.singularity.jetta.compiler.frontend.ir

class Lambda(
    override val params: List<Variable>,
    override var arrowType: ArrowType?,
    override val body: Atom,
    override val position: SourcePosition?
) : FunctionLike {
    /**
     * Name of the private static method on the enclosing class that holds
     * this lambda's body. Set by [net.singularity.jetta.compiler.backend.Generator]
     * during lambda discovery and consumed at the indy creation site.
     */
    var resolvedMethodName: String? = null

    override val returnType: Atom?
        get() = arrowType?.types?.last()

    override var type: Atom? = arrowType

    override val id: Int = UniqueAtomIdGenerator.generate()

    override fun toString(): String {
        return "Lambda(params=$params, arrowType=$arrowType, body=$body, position=$position)"
    }

    fun copy(body: Atom) = Lambda(params, arrowType, body, position)
}
