package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Special
import net.singularity.jetta.compiler.frontend.ir.Symbol

/**
 * Runtime support for MeTTa's non-determinism primitives `superpose` and `collapse`
 * (hyperon stdlib). They convert between a tuple and a non-deterministic bag of results,
 * which JeTTa represents as a [List] of [Atom]s.
 */
object Convert {
    /**
     * Strip a value out of its MeTTa envelope, if it has one.
     *
     * An `Object`-typed slot — what a parameter or return declared `Any` (or `Number`, which
     * erases to it) compiles to — may legitimately hold either a `Grounded` wrapping the value or
     * the bare box: the call site boxes a computed primitive, while a value that travelled as data
     * arrives wrapped. Code that reads such a slot as a primitive has to accept both, so it goes
     * through here instead of casting straight to `Grounded`. `Atom`-typed slots hold Atoms by
     * construction and keep their direct unwrap — this is not on that path.
     */
    @JvmStatic
    fun unwrapValue(value: Any?): Any? = when (value) {
        is BoundAtom -> unwrapValue(value.atom)
        is net.singularity.jetta.compiler.frontend.ir.Grounded<*> -> value.value
        else -> value
    }

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
     * `empty` — the empty non-deterministic result (hyperon stdlib): zero branches, i.e. an
     * empty bag. Used to PRUNE a branch — `(if (> $d 0) … (empty))` contributes nothing at the
     * base case, so a `flat-map?`/`map?` over it drops that alternative entirely (rather than
     * carrying an inert `(empty)` datum forward). Equivalent to `(superpose ())`.
     */
    @JvmStatic
    fun empty(): List<Atom> = emptyList()

    /**
     * `unique` — a non-determinism barrier that removes DUPLICATE results, keeping the distinct
     * ones (still a multivalued bag). Consumes the whole bag of its argument (like [collapse] /
     * [once]) — arriving as a [List] when the argument is multivalued, a bare [Atom] otherwise —
     * and dedupes by structural [Atom] equality, preserving first-seen order.
     */
    /**
     * `unquote` — strip ONE `quote` layer: `(quote X) → X`. The runtime half of a Form-2
     * pattern-`let` `(let (quote $v) VAL BODY)`, which binds `$v` to the CONTENT of VAL's quote
     * (LetRewriter lowers it to `(let $v (unquote VAL) BODY)`). A value that is not a
     * `(quote X)` is returned unchanged — the pattern simply didn't match a quote wrapper.
     */
    @JvmStatic
    fun unquote(value: Any?): Atom {
        val a = (if (value is BoundAtom) value.atom else value) as Atom
        return if (a is Expression && a.atoms.size == 2 && isQuoteHead(a.atoms[0])) a.atoms[1] else a
    }

    private fun isQuoteHead(head: Atom): Boolean =
        (head as? Symbol)?.name == "quote" || (head as? Special)?.value == "quote"

    @JvmStatic
    fun unique(value: Any?): List<Atom> {
        val bag = when (value) {
            null -> return emptyList()
            is List<*> -> value
            else -> listOf(value)
        }
        val distinct = LinkedHashSet<Atom>()
        bag.forEach { distinct.add((if (it is BoundAtom) it.atom else it) as Atom) }
        return distinct.toList()
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
            is Expression -> Expression(atom.atoms.sortedWith(::compareAtoms))
            else -> atom
        }
    }

    /**
     * Canonical structural order over atoms, matching hyperon's `msort`. Expressions are
     * compared element-wise with a shorter prefix ordering FIRST — so `(wu)` precedes
     * `(wu 42)` — rather than by raw text (where the `)` vs space at the divergence point
     * would flip them). Leaves fall back to their textual form and sort before expressions.
     */
    private fun compareAtoms(a: Atom, b: Atom): Int = when {
        a is Expression && b is Expression -> {
            val n = minOf(a.atoms.size, b.atoms.size)
            var i = 0
            var c = 0
            while (i < n && c == 0) { c = compareAtoms(a.atoms[i], b.atoms[i]); i++ }
            if (c != 0) c else a.atoms.size - b.atoms.size
        }
        a is Expression -> 1
        b is Expression -> -1
        else -> a.toString().compareTo(b.toString())
    }
}
