package net.singularity.jetta.compiler.frontend.ir


object Predefined {
    val PATTERN = Special("=")
    val TYPE = Special(":")
    val ARROW = Special("->")
    val PLUS = Special("+")
    val MINUS = Special("-")
    val TIMES = Special("*")
    val COND_EQ = Special("==")
    val COND_NEQ = Special("!=")
    val COND_GT = Special(">")
    val COND_GE = Special(">=")
    val COND_LT = Special("<")
    val COND_LE = Special("<=")
    val IF = Special("if")
    val TRUE = Grounded(true)
    val FALSE = Grounded(false)
    val AND = Special("and")
    val OR = Special("or")
    val NOT = Special("not")
    val XOR = Special("xor")
    val RUN_SEQ = Special("run-seq!")
}

fun Atom.isBooleanExpression(): Boolean =
    when (this) {
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