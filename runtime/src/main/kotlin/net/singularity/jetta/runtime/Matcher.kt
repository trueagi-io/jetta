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
    /**
     * Per-thread binding state, bundled in one holder so the hot path pays a single
     * `ThreadLocal.get()`. [bound] is the running total of entries across *all* frames,
     * maintained at every mutation site; `bound == 0` is therefore an O(1) test for
     * "nothing is bound anywhere" — see [allFramesEmpty]. (It replaces an O(depth) scan
     * that profiled at ~19% of the synth benchmark: there the binding stack is ~48 frames
     * deep on average and almost always entirely empty, so the scan walked ~48 empty maps
     * on every map?/flat-map? materialisation only to conclude "nothing to substitute".)
     * The frame maps are plain [HashMap]s (not insertion-ordered LinkedHashMaps): no code
     * depends on frame iteration order, and putAll/alloc are cheaper.
     */
    private class Frames {
        @JvmField val stack = ArrayDeque<MutableMap<String, Atom>>().also { it.addLast(HashMap()) }
        @JvmField var bound = 0
    }
    private val frames = ThreadLocal.withInitial { Frames() }
    private val log = Logger.getLogger(Matcher::class.java)

    @JvmStatic
    fun push() {
        val f = frames.get()
        val d = f.stack.size - 1
        f.stack.addLast(HashMap())
        if (log.isTraceEnabled) log.trace {
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
        val f = frames.get()
        val stack = f.stack
        val childFrame = stack.removeLast()
        // Propagate bindings from child to parent so callers can see bindings produced by
        // callees (e.g. match results) — a captured Variable in a lambda must resolve to a
        // value bound deep in a callee chain. (Never fires in the tracked benchmarks, where
        // child frames are always empty, but it is load-bearing for BackchainWho-style deep
        // binding propagation, so it stays.) Keep [bound] in sync: colliding keys merged
        // into the parent shrink the total, and the discarded child removes its own entries.
        val childSize = childFrame.size
        if (childSize != 0 && stack.isNotEmpty()) {
            val parent = stack.last()
            val before = parent.size
            parent.putAll(childFrame)
            f.bound += parent.size - before
        }
        f.bound -= childSize
        if (log.isTraceEnabled) log.trace {
            val caller = try {
                throw Exception()
            } catch (e: Exception) {
                e.stackTrace.getOrNull(3)
            }
            val indent = "  ".repeat(stack.size - 1)
            "|call| ${indent}pop  #${stack.size - 1} <- ${caller ?: "unknown"} (propagated: ${childFrame.keys})"
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
        val f = frames.get()
        f.stack.forEach { it.clear() }
        f.bound = 0
    }

    /**
     * The current (top) frame. Read-only for callers: every *write* must go through
     * [setBinding] / [installBindings] / [clearTop] (or [pop]) so the [Frames.bound]
     * counter stays exact — mutating the returned map directly would desync it and make
     * [allFramesEmpty] lie.
     */
    @JvmStatic
    fun getBindings(): MutableMap<String, Atom> = frames.get().stack.last()

    @JvmStatic
    fun setBinding(name: String, value: Atom) {
        if (log.isTraceEnabled) log.trace { "|bind| $name = $value" }
        val f = frames.get()
        if (f.stack.last().put(name, value) == null) f.bound++
    }

    /** Bulk-install [bindings] into the current (top) frame, keeping [Frames.bound] in sync. */
    @JvmStatic
    fun installBindings(bindings: Map<String, Atom>) {
        if (bindings.isEmpty()) return
        val f = frames.get()
        val top = f.stack.last()
        val before = top.size
        top.putAll(bindings)
        f.bound += top.size - before
    }

    /**
     * Materialise AND foliate a non-deterministic branch result in a single ThreadLocal read
     * (the non-det combinators call this once per branch result — a hot path). Two jobs:
     *  1. Substitute the branch's bound variables into the result (as [resolveDeep] does): a
     *     plain Atom like `(stop $z)` with `$z` bound becomes `(stop ventilation)`.
     *  2. FOLIATE the branch's bindings onto the result so they survive to downstream
     *     map?/flat-map? stages. Each branch runs under its own pushed frame, so the TOP frame
     *     holds exactly the vars this branch bound (its source [BoundAtom]'s bindings, installed
     *     at entry, plus anything its callees propagated up into it). Wrapping the result in a
     *     [BoundAtom] carrying that frame keeps sibling branches isolated: the alternative,
     *     [pop]'s upward merge, is last-wins and cannot tell two sibling branches apart, so a
     *     free var shared across a non-deterministic conjunction (e.g. `(And (croaks $x)
     *     (eat_flies $x))` matching several facts) would collapse to the last branch's binding.
     *
     * When nothing is bound anywhere (`bound == 0`, the common case) the value is returned
     * untouched — identical to the old resolve fast path, so the hot loop pays nothing. A
     * result that is ALREADY a [BoundAtom] keeps its own bindings, merged OVER the branch
     * frame so the more specific match bindings win.
     */
    @JvmStatic
    fun foliate(value: Any?): Any? {
        val f = frames.get()
        if (value is BoundAtom) {
            val top = f.stack.last()
            if (top.isEmpty()) return value
            val merged = HashMap<String, Atom>(top)
            merged.putAll(value.bindings)
            return BoundAtom(value.atom, merged)
        }
        if (f.bound == 0) return value // nothing to resolve or foliate (hot path)
        val top = f.stack.last()
        if (top.isEmpty()) return if (value is Atom) resolveDeepRec(value) else value
        // A branch result that is NOT an Atom — a raw boxed primitive (java.lang.Boolean /
        // Integer / …) produced by a VALUE-typed multivalued branch, e.g. green's identity
        // `map?` over a Bool, whose lambda returns a java.lang.Boolean. foliate cannot wrap a
        // non-Atom in a BoundAtom, so this branch's bindings (its own $x) would be lost and
        // pop's last-wins upward merge would then collapse sibling branches to the last one's
        // binding ([Sam, Sam] instead of [Fritz, Sam]). Wrap it in a Grounded (an Atom) first
        // so its bindings foliate like any structural (Symbol/Expression) branch result.
        val resolved: Atom = if (value is Atom) resolveDeepRec(value) else Grounded(value)
        return BoundAtom(resolved, HashMap(top))
    }

    /** Clear only the current (top) frame, keeping [Frames.bound] in sync. */
    @JvmStatic
    fun clearTop() {
        val f = frames.get()
        val top = f.stack.last()
        f.bound -= top.size
        top.clear()
    }

    // called only from JeTTa programs
    @Suppress("unused")
    @JvmStatic
    fun resolveBinding(atom: Atom): Atom {
        val target = if (atom is BoundAtom) {
            installBindings(atom.bindings)
            atom.atom
        } else atom

        if (target is Variable) {
            // Search from top of stack downward
            val stack = frames.get().stack
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

    // O(1): the invariant `bound == 0` means every frame on the stack is empty. See [Frames].
    private fun allFramesEmpty(): Boolean = frames.get().bound == 0

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
                // Indexed loop, not `zip().all{}`: this is a hot recursive equality check
                // (deduce's dispatch guards call it per node), and zip allocates a List<Pair>
                // + iterator at every expression level. Profiled at ~17% of a backchain query.
                for (i in pattern.atoms.indices) {
                    if (!structuralMatch(candidate.atoms[i], pattern.atoms[i])) return false
                }
                true
            }
            else -> candidate == pattern
        }
    }
}