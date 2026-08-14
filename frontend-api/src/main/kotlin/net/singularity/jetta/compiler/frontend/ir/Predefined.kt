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
    const val PACKAGE = "package"
    const val QUOTE = "quote"
    const val SELF = "&self"
}

object PredefinedAtoms {
    val MAP_ = Special(Predefined.MAP_)
    val FLAT_MAP_ = Special(Predefined.FLAT_MAP_)
    val MULTIVALUED = Symbol("multivalued")
    val EXPORT = Symbol("export")

    /**
     * Marks a `=` rule whose name JeTTa grounds natively: resolution prefers the builtin, so the
     * definition is unreachable as a CALL and codegen emits no method for it. The rule itself is
     * still a space atom, so the reflective path is unaffected.
     */
    val SHADOWED_BY_RUNTIME = Symbol("shadowed-by-runtime")
    val QUOTE = Special(Predefined.QUOTE)
}

/**
 * True when this expression applies a grounded operator to a number of operands it cannot take.
 * Almost always a PARTIAL APPLICATION on its way to a higher-order function — `(mymap (== 1) …)`
 * passes `==` with one operand, to be completed later by variable-head dispatch.
 *
 * Such a form is DATA. Every place that reacts to a grounded operator — the resolver's `Special`
 * branches, `CanonicalFormRewriter`'s `if` handling, codegen's operator dispatch — immediately
 * destructures a fixed shape, so each has to agree on when the shape is absent; that is why this
 * lives beside [Predefined] rather than in any one pass. Operators not listed are variadic and
 * have no shape to check (`seq`, `run-seq!`, `map?`, `quote`, the annotation heads).
 */
fun Expression.isMisappliedSpecial(): Boolean {
    val op = (atoms.firstOrNull() as? Special)?.value ?: return false
    val operands = atoms.size - 1
    return when (op) {
        Predefined.IF -> operands != 3
        Predefined.NOT -> operands != 1
        Predefined.COND_EQ, Predefined.COND_NEQ,
        Predefined.COND_LT, Predefined.COND_GT, Predefined.COND_LE, Predefined.COND_GE,
        Predefined.DIVIDE, Predefined.DIV, Predefined.MOD,
        Predefined.AND, Predefined.OR, Predefined.XOR -> operands != 2
        // `+`/`-`/`*` fold over their whole operand list, so any arity from two up is in shape;
        // codegen emits the first two and folds the rest. One operand is a partial application.
        Predefined.PLUS, Predefined.MINUS, Predefined.TIMES -> operands < 2
        else -> false
    }
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