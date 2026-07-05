package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.compiler.logger.Logger
import net.singularity.jetta.runtime.Matcher
import net.singularity.jetta.runtime.space.atoms.SAtom
import net.singularity.jetta.runtime.space.atoms.toAtom
import net.singularity.jetta.runtime.space.atoms.toSAtom

class SpaceImpl : Space {
    private val store = mutableListOf<Expression>()
    private val indexers = mutableMapOf<Expression, IndexerImpl>()
    private val logger = Logger.getLogger(SpaceImpl::class.java)

    /**
     * When true, match() wraps results in BoundAtom carrying per-result
     * binding snapshots (stack foliation). Required for per-call binding
     * semantics in the compiled pipeline.
     */
    var enablePerCallBindings: Boolean = true

    override fun add(expression: Expression) {
        val storeIndex = store.size
        store.add(expression)
        // Keep every already-built index live: fold the new atom into each cached indexer
        // incrementally (O(cached patterns), no store rescan) instead of invalidating. This
        // is what makes a `match` immediately after an `add-atom` observe the new fact — for
        // both lazily-built and pre-compiled (.jtsi) indexers, which all live in `indexers`.
        indexers.values.forEach { it.indexOne(this, expression, storeIndex) }
    }

    /**
     * Remove the first structurally-equal stored atom. Removal shifts every later store
     * position, invalidating the store-index references baked into the packed indexers, so
     * the cached indexers are rebuilt against the compacted store. `remove-atom` is far
     * rarer than `add-atom` (and seldom in a hot loop), so a rebuild here is an acceptable
     * trade for keeping `add` incremental. Structural equality (Expression.equals compares
     * atoms, ignoring id/type) matches the freshly-parsed atom against the stored one.
     */
    override fun remove(expression: Expression): Boolean {
        val idx = store.indexOfFirst { it == expression }
        if (idx < 0) return false
        store.removeAt(idx)
        indexers.values.forEach { it.index(this, it.pattern) }
        return true
    }

    override fun getAtoms(): List<Expression> = store.toList()

    override fun mkIndex(patterns: List<Expression>) {
        patterns.forEach { pattern ->
            val indexer = IndexerImpl(pattern)
            indexer.index(this, pattern)
            indexers[pattern] = indexer
        }
    }

    override fun contains(id: Int): Boolean {
        return store.find { it.id == id } != null
    }

    override fun match(
        src: Expression,
        dst: Atom
    ): List<Atom> {
        // No save/restore here — bindings must be visible to the caller after match returns

        // Conjunction pattern `(, p1 p2 ... pn)` — match each sub-pattern, with
        // shared query variables forced to agree. `,` is a regular Symbol
        // (not a Special); the conjunction semantics live in this operator,
        // not at the AST/parser level. See matchConjunction for the algorithm.
        if (isConjunction(src)) {
            return matchConjunction(src, dst)
        }

        // Get or create indexer for this pattern
        val indexer = indexers.getOrPut(src) {
            IndexerImpl(src).also {
                it.index(this, src)
            }
        }

        // Get packed index
        val packedIndex = indexer.getPackedIndex()

        logger.trace { "|match| pattern=$src template=$dst matches=${packedIndex.size()}" }

        return (0 until packedIndex.size()).map { matchIndex ->
            val bindings = packedIndex.resolve(matchIndex, this)
            val result = substituteVariables(dst, bindings)
            val spaceVarSubs = packedIndex.getSpaceVarSubstitutions(matchIndex)
            val final = applySpaceVarSubstitutions(result, spaceVarSubs)
            // Capture per-result bindings as a foliation leaf
            if (enablePerCallBindings) {
                val snapshot = mutableMapOf<String, Atom>()
                collectBindings(src, bindings, snapshot)
                writeBindingsToVariables(src, bindings)
                logger.trace { "|match| result[$matchIndex] = $final (spaceVarSubs=$spaceVarSubs)" }
                BoundAtom(final, snapshot)
            } else {
                final
            }
        }
    }

    private fun collectBindings(pattern: Atom, bindings: Bindings, out: MutableMap<String, Atom>) {
        when (pattern) {
            is Variable -> bindings[pattern.name]?.let { out[pattern.name] = it.toAtom() }
            is Expression -> pattern.atoms.forEach { collectBindings(it, bindings, out) }
            else -> {}
        }
    }

    private fun writeBindingsToVariables(pattern: Atom, bindings: Bindings) {
        when (pattern) {
            is Variable -> {
                bindings[pattern.name]?.let {
                    val bound = it.toAtom()
                    Matcher.setBinding(pattern.name, bound)
                }
            }

            is Expression -> {
                pattern.atoms.forEach { writeBindingsToVariables(it, bindings) }
            }

            else -> {}
        }
    }

    private fun applySpaceVarSubstitutions(atom: Atom, subs: Map<String, SAtom>): Atom {
        if (subs.isEmpty()) return atom
        return when (atom) {
            is Variable -> subs[atom.name]?.toAtom() ?: atom
            is Expression -> Expression(
                atoms = atom.atoms.map { applySpaceVarSubstitutions(it, subs) },
                type = atom.type,
                resolved = atom.resolved
            )

            else -> atom
        }
    }

    /**
     * Extract an atom from a stored expression using a packed binding.
     */
    fun extractAtom(packed: PackedBinding): SAtom {
        var current: Atom = store[packed.storeIndex]

        for (index in packed.atomPath) {
            current = when (current) {
                is Expression -> current.atoms[index]
                else -> throw IllegalStateException("Invalid path in PackedBinding: cannot traverse non-expression")
            }
        }
        return current.toSAtom()
    }

    private fun substituteVariables(atom: Atom, bindings: Bindings): Atom {
        return when (atom) {
            is Variable -> {
                // Variables not in this match's bindings stay as Variables — they
                // belong to a different scope (e.g. a nested `match` template
                // whose own variables are bound by the inner call, not the outer).
                bindings[atom.name]?.toAtom() ?: atom
            }

            is Expression -> {
                Expression(
                    atoms = atom.atoms.map { substituteVariables(it, bindings) },
                    type = atom.type,
                    resolved = atom.resolved
                )
            }

            else -> atom
        }
    }

    override fun chunks(numberOfChunks: Int): List<Iterator<Expression>> {
        require(numberOfChunks > 0) { "Number of chunks must be positive" }

        if (store.isEmpty()) {
            return List(numberOfChunks) { emptyList<Expression>().iterator() }
        }

        val chunkSize = (store.size + numberOfChunks - 1) / numberOfChunks // Ceiling division

        return (0 until numberOfChunks).map { chunkIndex ->
            val startIndex = chunkIndex * chunkSize
            val endIndex = minOf(startIndex + chunkSize, store.size)

            if (startIndex < store.size) {
                store.subList(startIndex, endIndex).iterator()
            } else {
                emptyList<Expression>().iterator()
            }
        }
    }

    /**
     * Get store size for indexing purposes.
     */
    internal fun getStoreSize(): Int = store.size

    // ---------------------------------------------------------------------
    // Conjunction matching: `(, p1 p2 ... pn)` patterns
    // ---------------------------------------------------------------------
    //
    // The conjunction operator inside a `match` pattern means: find variable
    // assignments that satisfy every sub-pattern p1..pn simultaneously, with
    // shared variables forced to agree.
    //
    // ALGORITHM: join-by-substitution.
    //
    // Naive approach: match each pi independently against the store, take the
    // cartesian product, filter rows where shared variables disagree. Correct
    // but the per-pattern indexers walk the whole store regardless of what
    // earlier patterns already constrained. With N sub-patterns this is
    // wasteful — most rows of the product get filtered out.
    //
    // Our approach: accumulate query-variable bindings as we go, and BAKE THEM
    // INTO THE NEXT SUB-PATTERN BEFORE running its indexer. Suppose p1 binds
    // $x=Sam. Then in p2 every occurrence of $x is literally replaced with the
    // symbol `Sam` before matching, so p2's indexer only returns store facts
    // that agree on $x=Sam. The shared-variable agreement becomes a partial
    // evaluation of the next pattern, not a post-filter — same architecture
    // pattern as the rest of JeTTa (partial eval with runtime fallback).
    //
    // STATE: `partial` is a list of "branches" — each branch is a Map<String,
    // Atom> covering every query variable seen in p1..p_{i-1}. We start with
    // a single empty branch (vacuous truth) and grow/prune at each step.
    //
    // SUBSTITUTION RULE (substituteConcreteBindings): substitute a query
    // variable in the next pattern only when its bound value is CONCRETE
    // (not itself a Variable). Why: a binding can be a Variable atom when an
    // earlier pattern unified a query var against a stored variable (no
    // concrete value to bind to). Substituting that stored-variable name into
    // the next pattern would inject a fresh symbolic name that the next
    // indexer would treat as a query variable, potentially clashing with
    // other vars. Leaving the query var as-is lets the next indexer re-bind
    // it freely, possibly to a concrete from a different stored fact.
    //
    // MERGE RULE (mergeBindings): for each query var in both branches:
    //   - prefer concrete over Variable (concrete wins);
    //   - two Variables → keep the existing one (both symbolic-free);
    //   - two equal concretes → no-op;
    //   - two unequal concretes → conflict, drop this combination.
    // The substitution rule above makes concrete-vs-concrete conflict
    // UNREACHABLE in practice — once a query var has a concrete value it gets
    // substituted out of the next pattern, so the next match can't produce a
    // different concrete for it. The check is defensive against future changes
    // to the substitution rule.
    //
    // dst RENDERING: after the join loop, each surviving branch's bindings are
    // applied to `dst` using applyBindingMap (the same shape as
    // substituteVariables in the single-pattern path). When per-call binding
    // foliation is on, we wrap each result in BoundAtom and also push the
    // bindings up via Matcher.setBinding — mirroring the upward-propagation
    // contract of the single-pattern path (see Matcher.pop in CLAUDE.md).
    //
    // NESTED CONJUNCTIONS: `matchOneAsBindings` checks for `(, ...)` patterns
    // and recurses through joinConjunctionBranches, so `(, (, a b) c)` works
    // transparently. Nested conjunctions are degenerate (could be flattened
    // by a rewriter) but the runtime handles them anyway.

    private fun isConjunction(src: Expression): Boolean {
        val head = src.atoms.firstOrNull() ?: return false
        return head is Symbol && head.name == ","
    }

    private fun matchConjunction(src: Expression, dst: Atom): List<Atom> {
        // Drop the leading `,` symbol — only the sub-patterns participate in the join.
        val branches = joinConjunctionBranches(src.atoms.drop(1))

        return branches.map { b ->
            // Mirror the single-pattern path: render dst with the branch's bindings,
            // optionally wrap in BoundAtom carrying the binding snapshot, and propagate
            // bindings up through Matcher so caller scopes can observe them.
            val rendered = applyBindingMap(dst, b)
            if (enablePerCallBindings) {
                b.forEach { (name, atom) -> Matcher.setBinding(name, atom) }
                logger.trace { "|matchConjunction| branch bindings=$b result=$rendered" }
                BoundAtom(rendered, b.toMutableMap())
            } else {
                rendered
            }
        }
    }

    /**
     * Core join loop, shared between `matchConjunction` and the nested-conjunction
     * case in `matchOneAsBindings`. Returns the list of binding-map branches that
     * survive after sequentially constraining on each sub-pattern. See the comment
     * block above the conjunction section for the full algorithm description.
     */
    private fun joinConjunctionBranches(subPatterns: List<Atom>): List<Map<String, Atom>> {
        // Seed: one empty branch. The first sub-pattern's matches will replace
        // this with the initial real binding set; no special-casing for "first
        // iteration" is needed.
        var partial: List<Map<String, Atom>> = listOf(emptyMap())

        for (sub in subPatterns) {
            if (sub !is Expression) {
                // Non-Expression conjunct is ill-formed (e.g. a bare Variable
                // under `,`). Nothing meaningful to match against the store —
                // skip it but log so we notice if it ever shows up.
                logger.warn { "|matchConjunction| skipping non-Expression sub-pattern: $sub" }
                continue
            }

            val next = mutableListOf<Map<String, Atom>>()

            for (b in partial) {
                // Specialize this sub-pattern with the concrete bindings from b.
                // This is where shared-variable agreement gets enforced — by the
                // time the indexer runs, `sub`'s variables that already have a
                // concrete value are gone, replaced by literal atoms.
                val specialized = substituteConcreteBindings(sub, b) as? Expression
                    ?: continue  // defensive: substituteConcreteBindings on Expression always yields Expression

                for (m in matchOneAsBindings(specialized)) {
                    // Combine the prior bindings b with this match's bindings m.
                    // Cannot conflict on concrete values given the substitution
                    // invariant; ?: continue is defensive.
                    val merged = mergeBindings(b, m) ?: continue
                    next.add(merged)
                }
            }

            partial = next
            // Short-circuit: if any sub-pattern wipes out every branch, no later
            // sub-pattern can resurrect them — bail out early.
            if (partial.isEmpty()) break
        }

        return partial
    }

    /**
     * Run the indexer pipeline against a single pattern, but return the raw
     * query-variable bindings for each match instead of a rendered dst. Used
     * by the conjunction join loop to get per-branch bindings before deciding
     * how to combine them.
     *
     * Reuses the cached `indexers` map — patterns recurring across calls
     * (which is common for conjunction sub-patterns once specialized) reuse
     * the same packed index.
     *
     * Nested conjunction: if `pattern` is itself a `(, ...)`, recurse through
     * the join loop. This lets `(, (, a b) c)` work without flattening.
     */
    private fun matchOneAsBindings(pattern: Expression): List<Map<String, Atom>> {
        if (isConjunction(pattern)) {
            return joinConjunctionBranches(pattern.atoms.drop(1))
        }

        val indexer = indexers.getOrPut(pattern) {
            IndexerImpl(pattern).also { it.index(this, pattern) }
        }
        val packedIndex = indexer.getPackedIndex()
        val varNames = VariableSchema.fromPattern(pattern).variableNames

        return (0 until packedIndex.size()).map { mi ->
            // resolve() already applies space-var substitutions to leaf atoms,
            // so the values we see here are post-substitution.
            val bindings = packedIndex.resolve(mi, this)
            val out = mutableMapOf<String, Atom>()
            varNames.forEach { name ->
                bindings[name]?.let { sa -> out[name] = sa.toAtom() }
            }
            out
        }
    }

    /**
     * Walk an atom tree and replace each Variable with its bound value from `map`,
     * but ONLY when that value is itself not a Variable (i.e., it's concrete:
     * Symbol / Grounded / Expression / Special).
     *
     * Concrete-only substitution is the load-bearing invariant of the join
     * algorithm. See the conjunction section header for why Variables stay put.
     */
    private fun substituteConcreteBindings(atom: Atom, map: Map<String, Atom>): Atom {
        return when (atom) {
            is Variable -> {
                val bound = map[atom.name]
                if (bound != null && bound !is Variable) bound else atom
            }
            is Expression -> Expression(
                atoms = atom.atoms.map { substituteConcreteBindings(it, map) },
                type = atom.type,
                resolved = atom.resolved
            )
            else -> atom
        }
    }

    /**
     * Combine two branches' bindings. The result covers every variable in
     * either map. On overlap:
     *   - concrete vs Variable → concrete wins;
     *   - two Variables → keep `a`'s (both are symbolically free; choice is arbitrary);
     *   - two equal concretes → no-op;
     *   - two unequal concretes → return null (conflict).
     *
     * The substitution rule in joinConjunctionBranches makes the
     * concrete-vs-concrete conflict path UNREACHABLE: once a variable has a
     * concrete value in `a`, it's substituted out of the next pattern, so the
     * next indexer can't produce a different concrete for it in `b`. The
     * conflict branch is kept defensively in case the substitution rule is
     * ever loosened.
     */
    private fun mergeBindings(a: Map<String, Atom>, b: Map<String, Atom>): Map<String, Atom>? {
        val result = a.toMutableMap()
        for ((name, valueB) in b) {
            val valueA = result[name]
            when {
                valueA == null -> result[name] = valueB
                valueA is Variable -> result[name] = valueB  // prefer non-Variable (or other Variable — same effect)
                valueB is Variable -> { /* keep a — already non-Variable wins */ }
                valueA == valueB -> { /* equal concretes, no-op */ }
                else -> return null  // concrete-vs-concrete mismatch — unreachable under current substitution rule
            }
        }
        return result
    }

    /**
     * Apply a Map<String, Atom> of bindings to an atom skeleton. Counterpart of
     * substituteVariables (which takes a Bindings interface). Unlike
     * substituteConcreteBindings, this one substitutes ALL bound variables —
     * it's used to render the user-supplied `dst` template once the conjunction
     * join is complete, where any remaining unbound Variables stay as Variables.
     */
    private fun applyBindingMap(atom: Atom, map: Map<String, Atom>): Atom {
        return when (atom) {
            is Variable -> map[atom.name] ?: atom
            is Expression -> Expression(
                atoms = atom.atoms.map { applyBindingMap(it, map) },
                type = atom.type,
                resolved = atom.resolved
            )
            else -> atom
        }
    }

    companion object {
        private val instance = SpaceImpl()

        @JvmStatic
        fun getInstance(): Space {
            return instance
        }
    }
}