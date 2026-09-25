package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.runtime.functions.TypeEngine
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The reference stdlib's `*-math` operations and the `min-atom`/`max-atom` pair, as grounded
 * functions over `Atom`s.
 *
 * Typed `Atom -> Atom` rather than `double -> double` because the reference's answer types are
 * not uniform and depend on the ARGUMENT: `(abs-math -5)` is `5` and `(abs-math -5.5)` is `5.5`,
 * while `(sqrt-math 9)` is `3.0` — a `Double` from an `Int` operand. Every behaviour here was
 * read off hyperon 0.2.10 rather than assumed; the ones worth naming:
 *
 *  - `round-math` rounds AWAY FROM ZERO: `(round-math -5.5)` is `-6.0`, where `Math.round`
 *    would answer `-5` (it rounds half towards positive infinity).
 *  - `trunc-math` truncates TOWARDS zero, so `(trunc-math -5.6)` is `-5.0` — not `floor`.
 *  - `log-math` takes the BASE first: `(log-math 2 8)` is `3.0`.
 *  - `min-atom`/`max-atom` always answer a `Double`, even over all-integer input:
 *    `(min-atom (2 6 7))` is `2.0`.
 *  - a non-numeric operand is `(Error (op …) (BadArgType <pos> Number <actual>))`, the same
 *    shape [TypeEngine] builds for a declared arrow.
 */
object MathOps {

    // --- operand plumbing -----------------------------------------------------------------

    private fun unwrap(a: Any?): Any? = if (a is BoundAtom) a.atom else a

    private fun num(a: Any?): Number? = when (val v = unwrap(a)) {
        is Number -> v
        is Grounded<*> -> v.value as? Number
        else -> null
    }

    private fun bool(b: Boolean): Atom = Symbol(if (b) "True" else "False")

    /** The MeTTa type name of an operand, for the `BadArgType` actual position. */
    private fun typeNameOf(a: Any?): Atom = when (val v = unwrap(a)) {
        is Grounded<*> -> when (v.value) {
            is Int, is Long, is Double -> Symbol("Number")
            is String -> Symbol("String")
            is Boolean -> Symbol("Bool")
            else -> Symbol("%Undefined%")
        }
        is Number -> Symbol("Number")
        is String -> Symbol("String")
        else -> Symbol("%Undefined%")
    }

    private fun badArg(op: String, pos: Int, args: List<Atom>): Atom =
        TypeEngine.errorExpr(
            Expression(atoms = listOf(Symbol(op)) + args),
            TypeEngine.TypeError(pos, Symbol("Number"), typeNameOf(args[pos - 1])),
        )

    /** Apply [f] to one numeric operand, or answer the reference's `BadArgType` term. */
    private inline fun unary(op: String, a: Atom, f: (Double) -> Atom): Atom {
        val x = num(a) ?: return badArg(op, 1, listOf(a))
        return f(x.toDouble())
    }

    private inline fun binary(op: String, a: Atom, b: Atom, f: (Double, Double) -> Atom): Atom {
        val x = num(a) ?: return badArg(op, 1, listOf(a, b))
        val y = num(b) ?: return badArg(op, 2, listOf(a, b))
        return f(x.toDouble(), y.toDouble())
    }

    private fun dbl(v: Double): Atom = Grounded(v)

    // --- the operations -------------------------------------------------------------------

    @JvmStatic
    fun `pow-math`(a: Atom, b: Atom): Atom = binary("pow-math", a, b) { x, y -> dbl(Math.pow(x, y)) }

    /** Base first: `(log-math 2 8)` is `3.0`. */
    @JvmStatic
    fun `log-math`(base: Atom, x: Atom): Atom =
        binary("log-math", base, x) { b, v -> dbl(ln(v) / ln(b)) }

    @JvmStatic
    fun `sqrt-math`(a: Atom): Atom = unary("sqrt-math", a) { dbl(sqrt(it)) }

    /**
     * The one operation that PRESERVES the operand's type — `(abs-math -5)` is `5`, not `5.0`.
     * Everything else here widens to Double.
     */
    @JvmStatic
    fun `abs-math`(a: Atom): Atom {
        val n = num(a) ?: return badArg("abs-math", 1, listOf(a))
        return when (n) {
            is Int -> Grounded(abs(n))
            is Long -> Grounded(abs(n))
            else -> Grounded(abs(n.toDouble()))
        }
    }

    /** Towards zero, so `(trunc-math -5.6)` is `-5.0`. */
    @JvmStatic
    fun `trunc-math`(a: Atom): Atom =
        unary("trunc-math", a) { dbl(if (it < 0) ceil(it) else floor(it)) }

    @JvmStatic
    fun `ceil-math`(a: Atom): Atom = unary("ceil-math", a) { dbl(ceil(it)) }

    @JvmStatic
    fun `floor-math`(a: Atom): Atom = unary("floor-math", a) { dbl(floor(it)) }

    /**
     * Half AWAY FROM ZERO — `(round-math 5.5)` is `6.0` and `(round-math -5.5)` is `-6.0`.
     * `Math.round` rounds half towards positive infinity and would answer `-5` for the latter.
     */
    @JvmStatic
    fun `round-math`(a: Atom): Atom = unary("round-math", a) { x ->
        dbl(if (x < 0) -floor(-x + 0.5) else floor(x + 0.5))
    }

    @JvmStatic
    fun `sin-math`(a: Atom): Atom = unary("sin-math", a) { dbl(sin(it)) }

    @JvmStatic
    fun `asin-math`(a: Atom): Atom = unary("asin-math", a) { dbl(asin(it)) }

    @JvmStatic
    fun `cos-math`(a: Atom): Atom = unary("cos-math", a) { dbl(cos(it)) }

    @JvmStatic
    fun `acos-math`(a: Atom): Atom = unary("acos-math", a) { dbl(acos(it)) }

    @JvmStatic
    fun `tan-math`(a: Atom): Atom = unary("tan-math", a) { dbl(tan(it)) }

    @JvmStatic
    fun `atan-math`(a: Atom): Atom = unary("atan-math", a) { dbl(atan(it)) }

    @JvmStatic
    fun `isnan-math`(a: Atom): Atom = unary("isnan-math", a) { bool(it.isNaN()) }

    @JvmStatic
    fun `isinf-math`(a: Atom): Atom = unary("isinf-math", a) { bool(it.isInfinite()) }

    // --- min/max over an expression ---------------------------------------------------------

    private fun extremum(op: String, e: Atom, pick: (Double, Double) -> Double): Atom {
        val expr = unwrap(e) as? Expression
            ?: return badArg(op, 1, listOf(e))
        if (expr.atoms.isEmpty()) {
            return Expression(
                Symbol("Error"),
                Expression(atoms = listOf(Symbol(op), expr)),
                Symbol("EmptyExpression"),
            )
        }
        var acc: Double? = null
        for (a in expr.atoms) {
            val n = num(a) ?: return badArg(op, 1, listOf(e))
            acc = if (acc == null) n.toDouble() else pick(acc, n.toDouble())
        }
        return dbl(acc!!)
    }

    /** Always a `Double`, even over all-integer input: `(min-atom (2 6 7))` is `2.0`. */
    @JvmStatic
    fun `min-atom`(e: Atom): Atom = extremum("min-atom", e) { a, b -> if (b < a) b else a }

    @JvmStatic
    fun `max-atom`(e: Atom): Atom = extremum("max-atom", e) { a, b -> if (b > a) b else a }
}
