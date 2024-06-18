package net.singularity.jetta.compiler.frontend.ir

enum class GroundedType(private val typeName: String, override val position: SourcePosition? = null) : Atom {
    INT("Int"),
    BOOLEAN("Boolean"),
    DOUBLE("Double"),
    STRING("String"),
    UNIT("Unit");

    override var type: Atom? = null

    override fun toString(): String = typeName
}