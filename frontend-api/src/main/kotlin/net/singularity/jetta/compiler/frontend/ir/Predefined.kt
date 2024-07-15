package net.singularity.jetta.compiler.frontend.ir


object Predefined {
    val PATTERN = "="
    val TYPE = ":"
    val ARROW = "->"
    val PLUS = "+"
    val MINUS = "-"
    val TIMES = "*"
    val DIVIDE = "/"
    val COND_EQ = "=="
    val COND_NEQ = "!="
    val COND_GT = ">"
    val COND_GE = ">="
    val COND_LT = "<"
    val COND_LE = "<="
    val IF = "if"
    val TRUE = "true"
    val FALSE = "false"
    val AND = "and"
    val OR = "or"
    val NOT = "not"
    val XOR = "xor"
    val RUN_SEQ = "run-seq!"
    val LAMBDA = "\\"
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