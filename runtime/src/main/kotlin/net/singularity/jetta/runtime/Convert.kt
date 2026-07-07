package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression

/**
 * Runtime support for MeTTa's non-determinism primitives `superpose` and `collapse`
 * (hyperon stdlib). They convert between a tuple and a non-deterministic bag of results,
 * which JeTTa represents as a [List] of [Atom]s.
 */
object Convert {
    /**
     * `superpose` — turn a tuple into a nondeterministic result.
     * `(superpose (a b c))` yields the alternatives `a`, `b`, `c`; `(superpose ())`
     * yields no results. The argument is an [Expression] whose children become the
     * branches; a non-expression argument is returned as a single branch.
     */
    @JvmStatic
    fun superpose(tuple: Atom): List<Atom> =
        when (val t = if (tuple is BoundAtom) tuple.atom else tuple) {
            is Expression -> t.atoms
            else -> listOf(t)
        }

    /**
     * `collapse` — convert a nondeterministic result into a tuple.
     * It is a non-determinism barrier: the argument is evaluated and all of its results
     * are collected into a single [Expression]. A multivalued argument arrives as a
     * [List]; a single-valued one arrives as a bare [Atom]. Empty results collapse to
     * the empty tuple `()`; `(collapse (shape))` with no rule for `shape` yields
     * `((shape))`.
     */
    @JvmStatic
    fun collapse(value: Any?): Atom {
        fun unwrap(a: Any?): Atom {
            val x = if (a is BoundAtom) a.atom else a
            return x as Atom
        }
        return when (value) {
            null -> Expression(emptyList())
            is List<*> -> Expression(value.map { unwrap(it) })
            else -> Expression(listOf(unwrap(value)))
        }
    }

    /**
     * `once` — a non-determinism barrier that keeps only the FIRST result of its argument.
     * The multivalued argument arrives as the full [List] (like `collapse`); `once` returns
     * its first element (unwrapping the per-branch [BoundAtom] to the substituted value), or
     * the unit atom `()` when there are no results. Used to make a query deterministic, e.g.
     * `(once (match &self …))`.
     */
    @JvmStatic
    fun once(value: Any?): Atom {
        val first = when (value) {
            is List<*> -> value.firstOrNull()
            else -> value
        } ?: return Expression(emptyList())
        return (if (first is BoundAtom) first.atom else first) as Atom
    }

    /**
     * `msort` — sort the elements of a tuple into a canonical (deterministic) order, by the
     * atoms' textual form. Its typical use is `(msort (collapse …))`: `collapse` gathers a
     * nondeterministic bag into an [Expression], and `msort` makes that bag order-independent
     * so it can be compared against an expected literal. A non-Expression argument (already a
     * single value) is returned unchanged.
     */
    @JvmStatic
    fun msort(value: Any?): Atom {
        val atom = when (value) {
            is BoundAtom -> value.atom
            is Atom -> value
            is List<*> -> Expression(value.map { (if (it is BoundAtom) it.atom else it) as Atom })
            else -> return Expression(emptyList())
        }
        return when (atom) {
            is Expression -> Expression(atom.atoms.sortedBy { it.toString() })
            else -> atom
        }
    }
}
