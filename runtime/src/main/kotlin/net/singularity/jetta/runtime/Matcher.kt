package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.Space

object Matcher {
    private val bindingStack = ThreadLocal.withInitial<ArrayDeque<MutableMap<String, Atom>>> {
        ArrayDeque<MutableMap<String, Atom>>().also { it.addLast(mutableMapOf()) }
    }
    private val depth = ThreadLocal.withInitial { 0 }

    @JvmStatic
    fun push() {
        val d = depth.get()
        depth.set(d + 1)
        bindingStack.get().addLast(mutableMapOf())
        val caller = try {
            throw Exception()
        } catch (e: Exception) {
            e.stackTrace.getOrNull(1)
        }
        val indent = "  ".repeat(d)
        println("[CALL] ${indent}push #$d -> ${caller ?: "unknown"}")
    }

    @JvmStatic
    fun pop() {
        val d = depth.get() - 1
        depth.set(d)
        val stack = bindingStack.get()
        val childFrame = stack.removeLast()
        // Propagate bindings from child to parent so callers can see
        // bindings produced by callees (e.g., match results).
        // This is still needed for cases where a captured Variable in a lambda
        // must resolve to a value bound deep in a callee chain.
        if (stack.isNotEmpty()) {
            stack.last().putAll(childFrame)
        }
        val caller = try {
            throw Exception()
        } catch (e: Exception) {
            e.stackTrace.getOrNull(1)
        }
        val indent = "  ".repeat(d)
        println("[CALL] ${indent}pop  #$d <- ${caller ?: "unknown"} (propagated: ${childFrame.keys})")
    }

    @JvmStatic
    fun getBindings(): MutableMap<String, Atom> = bindingStack.get().last()

    @JvmStatic
    fun setBinding(name: String, value: Atom) {
        println("[BIND] $name = $value")
        bindingStack.get().last()[name] = value
    }

    // called only from JeTTa programs
    @Suppress("unused")
    @JvmStatic
    fun resolveBinding(atom: Atom): Atom {
        val target = if (atom is BoundAtom) {
            getBindings().putAll(atom.bindings)
            atom.atom
        } else atom

        if (target is Variable) {
            // Search from top of stack downward
            val stack = bindingStack.get()
            for (i in stack.indices.reversed()) {
                val bound = stack.elementAt(i)[target.name]
                if (bound != null) return bound
            }
        }
        return target
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