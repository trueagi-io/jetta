package net.singularity.jetta.compiler.frontend.ir

enum class GroundedType(private val typeName: String, override val position: SourcePosition? = null) : Atom {
    INT("Int"),
    LONG("Long"),
    BOOLEAN("Boolean"),
    DOUBLE("Double"),
    STRING("String"),
    ANY("Any"),
    UNIT("Unit"),
    SPACE("Space"),
    ATOM("Atom"),
    EXPRESSION("Expression"),
    LIST("List"),
    NOTHING("Nothing");

    override val id: Int = -1

    override var type: Atom? = null

    override fun toString(): String = typeName
}