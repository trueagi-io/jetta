package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Special
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable

/**
 * The reference stdlib's multiset operations over expressions: `unique-atom`, `union-atom`,
 * `intersection-atom`, `subtraction-atom`. Lives beside [MathOps] rather than in
 * `JettaProgram` (already 1100+ lines) with the `car-atom`/`decons-atom` family.
 *
 * They are MULTISET operations, not set operations, and hyperon's answers say so:
 * `(intersection-atom (a b c c) (b c c c d))` is `(b c c)` — `c` survives twice because both
 * sides hold it at least twice — and `(union-atom (a b b c) (b c c d))` is plain
 * concatenation, `(a b b c b c c d)`, keeping every duplicate. Only `unique-atom` collapses
 * repeats. Element order follows the FIRST operand.
 *
 * A non-expression operand answers `(Error (op …) "Atom is not an ExpressionAtom")`, hyperon's
 * own wording.
 */
object AtomSetOps {

    private fun unwrap(a: Atom): Atom = if (a is BoundAtom) a.atom else a

    /**
     * A structural key for multiset bookkeeping. `Expression` and `Symbol` do define structural
     * `equals`, but `Variable` does not (see `SpaceImpl`'s note on why its index is keyed by
     * `PatternKey`), and a `BoundAtom` wrapper would compare unequal to the atom it carries.
     * Normalising to plain data sidesteps all three.
     */
    private fun key(a: Atom): Any? = when (val v = unwrap(a)) {
        is Expression -> v.atoms.map { key(it) }
        is Symbol -> "S:" + v.name
        is Special -> "P:" + v.value
        is Variable -> "V:" + v.name
        is Grounded<*> -> v.value
        else -> v.toString()
    }

    private fun notAnExpression(op: String, args: List<Atom>): Atom =
        Expression(
            Symbol("Error"),
            Expression(atoms = listOf(Symbol(op)) + args),
            Grounded("Atom is not an ExpressionAtom"),
        )

    private fun elementsOf(a: Atom): List<Atom>? = (unwrap(a) as? Expression)?.atoms

    /** Duplicates removed, first occurrence kept, order preserved. */
    @JvmStatic
    fun `unique-atom`(a: Atom): Atom {
        val xs = elementsOf(a) ?: return notAnExpression("unique-atom", listOf(a))
        val seen = HashSet<Any?>()
        return Expression(atoms = xs.filter { seen.add(key(it)) })
    }

    /** Plain concatenation — duplicates on both sides survive. */
    @JvmStatic
    fun `union-atom`(a: Atom, b: Atom): Atom {
        val xs = elementsOf(a) ?: return notAnExpression("union-atom", listOf(a, b))
        val ys = elementsOf(b) ?: return notAnExpression("union-atom", listOf(a, b))
        return Expression(atoms = xs + ys)
    }

    /** Multiset intersection: an element survives as many times as both sides hold it. */
    @JvmStatic
    fun `intersection-atom`(a: Atom, b: Atom): Atom {
        val xs = elementsOf(a) ?: return notAnExpression("intersection-atom", listOf(a, b))
        val ys = elementsOf(b) ?: return notAnExpression("intersection-atom", listOf(a, b))
        val available = counts(ys)
        val out = ArrayList<Atom>()
        for (x in xs) {
            val k = key(x)
            val n = available[k] ?: 0
            if (n > 0) {
                available[k] = n - 1
                out.add(x)
            }
        }
        return Expression(atoms = out)
    }

    /** Multiset difference: each occurrence on the right cancels one occurrence on the left. */
    @JvmStatic
    fun `subtraction-atom`(a: Atom, b: Atom): Atom {
        val xs = elementsOf(a) ?: return notAnExpression("subtraction-atom", listOf(a, b))
        val ys = elementsOf(b) ?: return notAnExpression("subtraction-atom", listOf(a, b))
        val toRemove = counts(ys)
        val out = ArrayList<Atom>()
        for (x in xs) {
            val k = key(x)
            val n = toRemove[k] ?: 0
            if (n > 0) toRemove[k] = n - 1 else out.add(x)
        }
        return Expression(atoms = out)
    }

    private fun counts(xs: List<Atom>): HashMap<Any?, Int> {
        val m = HashMap<Any?, Int>()
        for (x in xs) m.merge(key(x), 1, Int::plus)
        return m
    }
}
