package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable

/**
 * Structural map key for a `match` pattern, so [SpaceImpl]'s indexer cache hits for
 * structurally-identical patterns. Needed because `Variable` has only identity equality:
 * an `Expression`-keyed map misses on every variable-bearing pattern (rebuilding its index
 * per query). PatternKey compares by structure — symbols and grounded values by value,
 * variables by NAME, expressions recursively — ignoring type/position/id.
 *
 * The hash is computed once at construction in a single tree walk (no intermediate string,
 * unlike an earlier canonical-string key that cost ~12% of a backchain query building and
 * re-hashing the string); [equals] runs the structural compare only on a hash match.
 */
internal class PatternKey(val pattern: Expression) {
    private val hash: Int = hashOf(pattern)

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean =
        this === other || (other is PatternKey && hash == other.hash && structuralEquals(pattern, other.pattern))

    private companion object {
        fun hashOf(atom: Atom): Int = when (atom) {
            is Variable -> 31 * 2 + atom.name.hashCode()
            is Symbol -> 31 * 3 + atom.name.hashCode()
            is Grounded<*> -> 31 * 5 + (atom.value?.hashCode() ?: 0)
            is Expression -> {
                var h = 1
                for (a in atom.atoms) h = 31 * h + hashOf(a)
                h
            }
            else -> 31 * 7 + atom.toString().hashCode()
        }

        fun structuralEquals(a: Atom, b: Atom): Boolean = when (a) {
            is Variable -> b is Variable && a.name == b.name
            is Symbol -> b is Symbol && a.name == b.name
            // Class-aware so grounded 5:Int and "5":String never share an index.
            is Grounded<*> -> b is Grounded<*> && a.value == b.value &&
                a.value?.javaClass == b.value?.javaClass
            is Expression -> {
                if (b !is Expression || a.atoms.size != b.atoms.size) return false
                for (i in a.atoms.indices) if (!structuralEquals(a.atoms[i], b.atoms[i])) return false
                true
            }
            else -> a == b
        }
    }
}
