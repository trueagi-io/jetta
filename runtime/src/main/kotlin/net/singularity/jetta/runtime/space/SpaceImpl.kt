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
    // Keyed by [PatternKey], NOT the pattern Expression: `Variable` has no structural equals
    // (identity only), so a `Map<Expression, _>` would MISS on every pattern containing a
    // variable — e.g. `(Implication $a (Evaluation (mortal p0)))` never matched a
    // structurally-identical earlier pattern, rebuilding its index on every query. PatternKey
    // makes structurally-equal patterns share one index via a cached structural hash — no
    // per-lookup string is built (which profiled at ~12% of a backchain query).
    private val indexers = mutableMapOf<PatternKey, IndexerImpl>()
    // One structural index over the whole store, maintained incrementally. Turns a lazy
    // pattern's candidate retrieval from an O(store) scan into an O(query) trie walk — the
    // difference between backward chaining costing O(store) per query and staying flat.
    private val disc = DiscriminationTrie()
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
        disc.insert(expression, storeIndex)
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
        // Removal shifts every later store index, so both the structural trie and the
        // packed indexers (whose PackedBindings reference store positions) must be rebuilt
        // against the compacted store. `remove-atom` is rare and off the hot path.
        disc.clear()
        store.forEachIndexed { i, atom -> disc.insert(atom, i) }
        indexers.values.forEach { it.index(this, it.pattern) }
        return true
    }

    override fun getAtoms(): List<Expression> = store.toList()

    override fun mkIndex(patterns: List<Expression>) {
        patterns.forEach { pattern ->
            val indexer = IndexerImpl(pattern)
            indexer.index(this, pattern)
            indexers[PatternKey(pattern)] = indexer
        }
    }

    /** Register a pre-built (deserialized `.jtsi`) indexer under its pattern's structural key. */
    internal fun registerPrebuiltIndexer(indexer: IndexerImpl) {
        indexers[PatternKey(indexer.pattern)] = indexer
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
        val indexer = indexers.getOrPut(PatternKey(src)) {
            IndexerImpl(src).also {
                it.index(this, src)
            }
        }

        // Get packed index
        val packedIndex = indexer.getPackedIndex()

        logger.trace { "|match| pattern=$src template=$dst matches=${packedIndex.size()}" }

        val size = packedIndex.size()

        // Foliation-elision fast path (single result). A BoundAtom exists only to give
        // each of SEVERAL non-deterministic branches its own binding snapshot to reinstall
        // in a child frame during map?/flat-map?. With exactly one result there is no other
        // branch to isolate from: writeBindingsToVariables already writes this result's
        // bindings into the current (caller's) frame, and resolveBinding/resolveDeep search
        // the whole stack, so any downstream stage sees them without a wrapper (and
        // materialize() resolves the plain atom against that frame immediately). This skips
        // a HashMap alloc + collectBindings walk + BoundAtom per single-result query — the
        // dominant per-call cost in deep backward chaining, where nearly every `match &self`
        // yields 0 or 1 rows. Zero results already allocate nothing (empty map below).
        if (enablePerCallBindings && size == 1) {
            // Atom-valued resolve: extract store atoms directly, with NO SAtom round-trip.
            // The old path went store-Atom -> SAtom (extractAtom) -> Atom (twice, in
            // substituteVariables + writeBindingsToVariables), a full recursive tree copy
            // each way per bound variable. `toAtom` profiled at ~13% of a deep-chain query.
            // Store IR atoms are immutable value objects, so sharing the reference is safe
            // (substituteVariables already reuses the dst leaf for unbound vars).
            val bindings = packedIndex.resolveToAtoms(0, this)
            val result = substituteVariablesA(dst, bindings)
            val spaceVarSubs = packedIndex.getSpaceVarSubstitutions(0)
            val final = applySpaceVarSubstitutions(result, spaceVarSubs)
            writeBindingsToVariablesA(src, bindings)
            logger.trace { "|match| result[0] = $final (spaceVarSubs=$spaceVarSubs, elided)" }
            return listOf(final)
        }

        return (0 until size).map { matchIndex ->
            // Atom-valued resolve (see the size==1 fast path): the returned map is keyed by
            // exactly this pattern's variable names (schema == VariableSchema.fromPattern),
            // so it doubles as the BoundAtom foliation snapshot — no separate collectBindings
            // walk and no SAtom round-trip. Fresh per branch, so sharing it with the snapshot
            // is safe (BoundAtom bindings are read-only downstream).
            val bindings = packedIndex.resolveToAtoms(matchIndex, this)
            val result = substituteVariablesA(dst, bindings)
            val spaceVarSubs = packedIndex.getSpaceVarSubstitutions(matchIndex)
            val final = applySpaceVarSubstitutions(result, spaceVarSubs)
            if (enablePerCallBindings) {
                writeBindingsToVariablesA(src, bindings)
                logger.trace { "|match| result[$matchIndex] = $final (spaceVarSubs=$spaceVarSubs)" }
                BoundAtom(final, bindings)
            } else {
                final
            }
        }
    }

    /**
     * Write each of the pattern's bound variables up into the current Matcher frame, so the
     * caller observes bindings produced by this match (upward propagation — see Matcher.pop).
     * Atom-valued: the map holds raw store atoms, so there is no SAtom.toAtom() per binding.
     */
    private fun writeBindingsToVariablesA(pattern: Atom, bindings: Map<String, Atom>) {
        when (pattern) {
            is Variable -> bindings[pattern.name]?.let { Matcher.setBinding(pattern.name, it) }
            is Expression -> pattern.atoms.forEach { writeBindingsToVariablesA(it, bindings) }
            else -> {}
        }
    }

    /** Render `dst`, substituting each bound Variable with its Atom value (unbound vars stay). */
    private fun substituteVariablesA(atom: Atom, bindings: Map<String, Atom>): Atom {
        return when (atom) {
            is Variable -> bindings[atom.name] ?: atom
            is Expression -> Expression(
                atoms = atom.atoms.map { substituteVariablesA(it, bindings) },
                type = atom.type,
                resolved = atom.resolved
            )
            else -> atom
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

    /**
     * Like [extractAtom] but returns the raw store [Atom] with NO SAtom conversion. The
     * returned reference points into the immutable store IR; callers must treat it as
     * read-only (they do — it's only embedded into rendered results or set as a binding).
     */
    internal fun extractAtomRaw(packed: PackedBinding): Atom {
        var current: Atom = store[packed.storeIndex]
        for (index in packed.atomPath) {
            current = when (current) {
                is Expression -> current.atoms[index]
                else -> throw IllegalStateException("Invalid path in PackedBinding: cannot traverse non-expression")
            }
        }
        return current
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

    /** Store atom at [index] — for the indexer to re-check trie candidates. */
    internal fun storeAt(index: Int): Expression = store[index]

    /**
     * Store indices whose atom could match [pattern], via the structural trie — a superset
     * (structural compatibility only; exact variable-consistency check stays in the indexer).
     * Ascending, so the indexer's results keep store order. O(pattern), not O(store).
     */
    internal fun candidateIndices(pattern: Expression): List<Int> = disc.lookup(pattern)

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

        val indexer = indexers.getOrPut(PatternKey(pattern)) {
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