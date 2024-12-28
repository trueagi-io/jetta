package net.singularity.jetta.compiler.frontend.ir


object Predefined {
    const val ANNOTATION = "@"
    const val PATTERN = "="
    const val TYPE = ":"
    const val ARROW = "->"
    const val PLUS = "+"
    const val MINUS = "-"
    const val TIMES = "*"
    const val DIVIDE = "/"
    const val COND_EQ = "=="
    const val COND_NEQ = "!="
    const val COND_GT = ">"
    const val COND_GE = ">="
    const val COND_LT = "<"
    const val COND_LE = "<="
    const val IF = "if"
    const val TRUE = "true"
    const val FALSE = "false"
    const val AND = "and"
    const val OR = "or"
    const val NOT = "not"
    const val XOR = "xor"
    const val RUN_SEQ = "run-seq!"
    const val LAMBDA = "\\"
    const val DIV = "div"
    const val MOD = "mod"
    const val MAP_ = "map?"
    const val FLAT_MAP_ = "flat-map?"
    const val SEQ = "seq"
}

object PredefinedAtoms {
    val MAP_ = Special(Predefined.MAP_)
    val FLAT_MAP_ = Special(Predefined.FLAT_MAP_)
    val MULTIVALUED = Symbol("multivalued")
}

fun Atom.isBooleanExpression(): Boolean =
    if (this is Special) {
        when (this.value) {
            Predefined.COND_EQ,
            Predefined.COND_NEQ,
            Predefined.COND_GT,
            Predefined.COND_GE,
            Predefined.COND_LT,
            Predefined.COND_LE,
            Predefined.AND,
            Predefined.OR,
            Predefined.NOT,
            Predefined.XOR -> true

            else -> false
        }
    } else false