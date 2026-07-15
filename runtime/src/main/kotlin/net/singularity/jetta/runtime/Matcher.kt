package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.compiler.logger.Logger
import net.singularity.jetta.runtime.space.Space

object Matcher {
    private val bindingStack = ThreadLocal.withInitial {
        ArrayDeque<MutableMap<String, Atom>>().also { it.addLast(mutableMapOf()) }
    }
    private val depth = ThreadLocal.withInitial { 0 }
    private val log = Logger.getLogger(Matcher::class.java)

    @JvmStatic
    fun push() {
        val d = depth.get()
        depth.set(d + 1)
        bindingStack.get().addLast(mutableMapOf())
        log.trace {
            val caller = try {
                throw Exception()
            } catch (e: Exception) {
                e.stackTrace.getOrNull(3)
            }
            val indent = "  ".repeat(d)
            "|call| ${indent}push #$d -> ${caller ?: "unknown"}"
        }
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
        log.trace {
            val caller = try {
                throw Exception()
            } catch (e: Exception) {
                e.stackTrace.getOrNull(3)
            }
            val indent = "  ".repeat(d)
            "|call| ${indent}pop  #$d <- ${caller ?: "unknown"} (propagated: ${childFrame.keys})"
        }
    }

    /**
     * Clear the CONTENTS of every binding frame, keeping the stack shape/depth intact
     * (so push/pop balance is preserved — safe to call mid-sequence, unlike [pop]).
     * Emitted between top-level `!` runs in `__main`: each run is an independent query,
     * so a variable bound in one must not leak into the next — e.g. b4's `(is (air dry))`
     * leaving `$y` bound when `(is (air wet))` then runs. (Spaces/states live elsewhere
     * and are unaffected.)
     */
    @JvmStatic
    fun clearAll() {
        bindingStack.get().forEach { it.clear() }
    }

    @JvmStatic
    fun getBindings(): MutableMap<String, Atom> = bindingStack.get().last()

    @JvmStatic
    fun setBinding(name: String, value: Atom) {
        log.trace { "|bind| $name = $value" }
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

    /**
     * Like [resolveBinding] but recurses into sub-expressions, so a bound variable
     * nested in a compound (e.g. `$z` in `(stop $z)`, or `$y` in `(making $y)`) is
     * substituted with its value. Free (unbound) variables are left intact. Used to
     * materialise a non-deterministic branch's result against the bindings its branch
     * installed — [resolveBinding] alone only handles a top-level variable.
     */
    fun resolveDeep(atom: Atom): Atom {
        // Fast path: if nothing is bound anywhere on the stack, no Variable can
        // resolve and there is nothing to substitute, so the full recursive tree
        // rebuild below is pure waste (it reallocates an identical Expression at
        // every node). This is the dominant cost of map?/flat-map? materialisation
        // when branches carry no bindings. A top-level BoundAtom still installs its
        // own bindings via resolveBinding, so exclude it from the shortcut; nested
        // BoundAtoms do not occur (they only ever wrap a top-level match result).
        if (atom !is BoundAtom && allFramesEmpty()) return atom
        return resolveDeepRec(atom)
    }

    private fun resolveDeepRec(atom: Atom): Atom = when (val resolved = resolveBinding(atom)) {
        is Expression -> Expression(resolved.atoms.map { resolveDeepRec(it) }, position = resolved.position)
        else -> resolved
    }

    private fun allFramesEmpty(): Boolean {
        val stack = bindingStack.get()
        for (frame in stack) if (frame.isNotEmpty()) return false
        return true
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