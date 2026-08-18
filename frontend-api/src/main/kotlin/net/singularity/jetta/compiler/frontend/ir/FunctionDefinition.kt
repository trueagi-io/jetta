package net.singularity.jetta.compiler.frontend.ir

data class FunctionDefinition(
    val name: String,
    override val params: List<Variable>,
    override var arrowType: ArrowType?,
    override val body: Atom,
    val annotations: MutableList<Atom> = mutableListOf(),
    override val position: SourcePosition? = null,
    /**
     * Indices of parameters the source declares LITERALLY as the meta-type `Atom` — the hyperon
     * annotation that suppresses reduction of the argument, so a call site passes the term rather
     * than its value. It cannot be read back off [arrowType]: `FunctionRewriter.asType()` erases
     * every type it does not know (`Number`, `Nat`, a user type) to `Atom` as well, and reducing IS
     * right for those. So the fact has to travel from the DECLARATION, which is what this records;
     * `Context` hands it to the callee's `JvmMethod.templateAtomParams`, whose doc explains why a
     * user function's argument is held only when it is a template rather than unconditionally.
     */
    val declaredAtomParams: Set<Int> = emptySet(),
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
