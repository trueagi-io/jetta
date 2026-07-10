package net.singularity.jetta.runtime.functions

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol

/**
 * Grounded arithmetic/comparison operators as individual static methods, registered in
 * [JettaLinkRegistry] as MethodHandles so an operator applied through a VARIABLE head —
 * `(ev (Bin $op $a $b) …) → ($op (ev $a …) (ev $b …))` with `$op` bound to `+`/`-`/`*` — is
 * dispatched by [JettaCallSite] as ONE indirect MethodHandle call, not a per-call operator
 * switch. Because each operator is its own method the handle points straight at it; P2's
 * polymorphic-inline-cache then pins a call site to the resolved handle and HotSpot inlines
 * the hot operator near-natively (the JSR-292 design — the same machinery user-fn linking and
 * Java lambdas use).
 *
 * Each method unwraps its operands from their `Grounded`/`BoundAtom` envelopes to numbers and
 * returns a `Grounded` result (arithmetic) or the `True`/`False` symbol (comparison). A
 * non-numeric operand (an unreduced expression, a bare symbol) yields null — "not computable"
 * — so [JettaCallSite.dispatch] leaves the application inert instead of forcing a result.
 * Integer operands stay Int; a Double operand promotes the result to Double.
 */
object GroundedOps {

    @JvmStatic fun plus(a: Any?, b: Any?): Atom? = arith(a, b, { x, y -> x + y }, { x, y -> x + y })
    @JvmStatic fun minus(a: Any?, b: Any?): Atom? = arith(a, b, { x, y -> x - y }, { x, y -> x - y })
    @JvmStatic fun times(a: Any?, b: Any?): Atom? = arith(a, b, { x, y -> x * y }, { x, y -> x * y })
    @JvmStatic fun div(a: Any?, b: Any?): Atom? {
        val x = num(a) ?: return null
        val y = num(b) ?: return null
        return if (isDouble(x, y)) Grounded(x.toDouble() / y.toDouble()) else Grounded(x.toInt() / y.toInt())
    }
    @JvmStatic fun mod(a: Any?, b: Any?): Atom? {
        val x = num(a) ?: return null
        val y = num(b) ?: return null
        return Grounded(x.toInt() % y.toInt())
    }

    @JvmStatic fun lt(a: Any?, b: Any?): Atom? = compare(a, b) { c -> c < 0 }
    @JvmStatic fun gt(a: Any?, b: Any?): Atom? = compare(a, b) { c -> c > 0 }
    @JvmStatic fun le(a: Any?, b: Any?): Atom? = compare(a, b) { c -> c <= 0 }
    @JvmStatic fun ge(a: Any?, b: Any?): Atom? = compare(a, b) { c -> c >= 0 }
    @JvmStatic fun eq(a: Any?, b: Any?): Atom? = compare(a, b) { c -> c == 0 }

    private inline fun arith(
        a: Any?, b: Any?,
        ints: (Int, Int) -> Int,
        doubles: (Double, Double) -> Double,
    ): Atom? {
        val x = num(a) ?: return null
        val y = num(b) ?: return null
        return if (isDouble(x, y)) Grounded(doubles(x.toDouble(), y.toDouble()))
        else Grounded(ints(x.toInt(), y.toInt()))
    }

    private inline fun compare(a: Any?, b: Any?, test: (Int) -> Boolean): Atom? {
        val x = num(a) ?: return null
        val y = num(b) ?: return null
        return Symbol(if (test(x.toDouble().compareTo(y.toDouble()))) "True" else "False")
    }

    private fun isDouble(a: Number, b: Number): Boolean = a is Double || b is Double

    private fun num(value: Any?): Number? = when (val v = if (value is BoundAtom) value.atom else value) {
        is Number -> v
        is Grounded<*> -> v.value as? Number
        else -> null
    }

    /** Operator symbol -> static method name, seeded into [JettaLinkRegistry]. */
    val OPS: Map<String, String> = mapOf(
        "+" to "plus", "-" to "minus", "*" to "times", "/" to "div", "%" to "mod",
        "<" to "lt", ">" to "gt", "<=" to "le", ">=" to "ge", "==" to "eq",
    )
}
