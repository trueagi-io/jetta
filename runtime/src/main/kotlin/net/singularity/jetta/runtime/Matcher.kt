package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.Space

object Matcher {
    private val bindingContext = ThreadLocal.withInitial<MutableMap<String, Atom>> { mutableMapOf() }

    @JvmStatic
    fun getBindings(): MutableMap<String, Atom> = bindingContext.get()

    @JvmStatic
    fun saveBindings(): Map<String, Atom> = HashMap(bindingContext.get())

    @JvmStatic
    fun restoreBindings(saved: Map<String, Atom>) {
        bindingContext.get().clear()
        bindingContext.get().putAll(saved)
    }

    @JvmStatic
    fun setBinding(name: String, value: Atom) {
        bindingContext.get()[name] = value
    }

    @JvmStatic
    fun resolveBinding(atom: Atom): Atom {
        if (atom is Variable) {
            val bound = bindingContext.get()[atom.name]
            if (bound != null) return bound
        }
        return atom
    }

    fun match(space: Space, src: Expression, dst: Atom): List<Atom> =
        space.match(src, dst)


    /**
     * Structural pattern match: checks if [candidate] matches [pattern].
     *
     * - [Variable] in the pattern matches any atom (wildcard).
     * - [Symbol] must match by name.
     * - [Grounded] must match by value.
     * - [Expression] must have the same arity and each atom must match recursively.
     *
     * This is used by the code generator to evaluate conditions on
     * destructured match branches, e.g. `$var0 == (Pair $a $b)`.
     */
    @JvmStatic
    fun structuralMatch(candidate: Atom, pattern: Atom): Boolean {
        return when (pattern) {
            is Variable -> true
            is Symbol -> candidate is Symbol && candidate.name == pattern.name
            is Grounded<*> -> candidate is Grounded<*> && candidate.value == pattern.value
            is Expression -> {
                if (candidate !is Expression) return false
                if (candidate.atoms.size != pattern.atoms.size) return false
                pattern.atoms.zip(candidate.atoms).all { (p, c) -> structuralMatch(c, p) }
            }
            else -> candidate == pattern
        }
    }

}