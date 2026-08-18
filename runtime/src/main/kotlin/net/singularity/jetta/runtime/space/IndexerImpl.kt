package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Special
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.atoms.SAtom
import net.singularity.jetta.runtime.space.atoms.toSAtom

class IndexerImpl(val pattern: Expression) : Indexer {

    private val schema: VariableSchema = VariableSchema.fromPattern(pattern)
    private val packedMatches = mutableListOf<PackedMatch>()
    private val spaceVarSubstitutions = mutableListOf<Map<String, SAtom>>()
    private var cachedSpace: SpaceImpl? = null

    override fun match(expr: Expression, bindings: Bindings): Boolean {
        if (!matchAndUnify(expr, bindings)) {
            bindings.clear()
            return false
        }
        return true
    }

    private fun matchAndUnify(expr: Expression, bindings: Bindings): Boolean {
        if (pattern.atoms.size != expr.atoms.size) return false
        repeat(pattern.atoms.size) {
            when (val atom = pattern.atoms[it]) {
                is Symbol -> {
                    when (val a = expr.atoms[it]) {
                        is Symbol -> if (a.name != atom.name) return false
                        is Variable -> {
                            // Space variable unifies with pattern symbol — always matches
                        }
                        else -> return false
                    }
                }

                is Expression -> {
                    val a = expr.atoms[it]
                    if (a is Variable) {
                        // Space variable unifies with pattern expression
                    } else if (a !is Expression) {
                        return false
                    } else {
                        val indexer = IndexerImpl(atom)
                        if (!indexer.matchAndUnify(a, bindings)) return false
                    }
                }

                is Variable -> {
                    val a = expr.atoms[it].toSAtom()
                    val b = bindings[atom.name]
                    if (b == null) {
                        bindings[atom.name] = expr.atoms[it].toSAtom()
                    } else {
                        if (a != b) {
                            return false
                        }
                    }
                }

                is Grounded<*> -> {
                    val a = expr.atoms[it]
                    if (a is Variable) {
                        // Space variable unifies with pattern grounded value
                    } else if (a !is Grounded<*>) {
                        return false
                    } else if (a.value != atom.value) {
                        return false
                    }
                }

                else -> return false
            }
        }
        return true
    }

    override fun index(space: Space, expr: Expression) {
        require(space is SpaceImpl) { "Space must be SpaceImpl for packed indexing" }

        cachedSpace = space

        packedMatches.clear()
        spaceVarSubstitutions.clear()

        // Ask the space's structural trie for the store atoms that could match this pattern —
        // an O(pattern) walk returning a superset in ascending store order — then run the
        // exact match/capture on just those. This replaces the old O(store) full scan (and
        // its per-build thread pool): on a large KB with varied queries it is the difference
        // between O(store) and ~O(pattern) per `match`. Candidates are structurally
        // compatible only; tryMatch enforces cross-position variable consistency.
        for (storeIndex in space.candidateIndices(pattern)) {
            tryMatch(space.storeAt(storeIndex), storeIndex, space)?.let { (match, subs) ->
                packedMatches.add(match)
                spaceVarSubstitutions.add(subs)
            }
        }
    }

    /**
     * Incrementally fold a single newly-added store atom into this already-built index,
     * without re-scanning the store. Called by [SpaceImpl.add] for every cached indexer so
     * that a `match` immediately following an `add-atom` sees the new fact — the packed
     * index stays live under runtime mutation instead of going stale. [storeIndex] must be
     * the atom's real position in the space store (the [PackedBinding]s baked here resolve
     * back through `store[storeIndex]`). No-op when the atom does not match this pattern.
     */
    fun indexOne(space: SpaceImpl, expr: Expression, storeIndex: Int) {
        cachedSpace = space
        tryMatch(expr, storeIndex, space)?.let { (match, subs) ->
            packedMatches.add(match)
            spaceVarSubstitutions.add(subs)
        }
    }

    private fun tryMatch(expr: Expression, storeIndex: Int, space: SpaceImpl): Pair<PackedMatch, Map<String, SAtom>>? {
        val bindings = Array<PackedBinding?>(schema.size()) { null }
        val spaceVarBindings = mutableMapOf<String, SAtom>()

        if (matchAndCapture(pattern, expr, storeIndex, intArrayOf(), bindings, space, spaceVarBindings)) {
            // Slots may legitimately stay null — a pattern sub-term unified with a store
            // VARIABLE binds that variable and leaves the sub-term's own variables free (see
            // [PackedMatch]). The array is handed over as-is; it used to be cast to a
            // non-null element type, which turned every such match into an NPE downstream.
            return PackedMatch(bindings) to spaceVarBindings
        }

        return null
    }

    private fun matchAndCapture(
        patternExpr: Expression,
        expr: Expression,
        storeIndex: Int,
        currentPath: IntArray,
        bindings: Array<PackedBinding?>,
        space: SpaceImpl,
        spaceVarBindings: MutableMap<String, SAtom>
    ): Boolean {
        if (patternExpr.atoms.size != expr.atoms.size) return false

        patternExpr.atoms.indices.forEach { i ->
            val patternAtom = patternExpr.atoms[i]
            val exprAtom = expr.atoms[i]
            val newPath = currentPath + i

            when (patternAtom) {
                is Variable -> {
                    val varIndex = schema.getIndex(patternAtom.name)
                    val existing = bindings[varIndex]
                    val current = PackedBinding(storeIndex, newPath)

                    if (existing == null) {
                        bindings[varIndex] = current
                    } else {
                        val existingValue = space.extractAtom(existing)
                        val currentValue = space.extractAtom(current)
                        if (existingValue != currentValue) {
                            return false
                        }
                    }
                }

                is Symbol -> {
                    if (exprAtom is Variable) {
                        val patternSAtom = patternAtom.toSAtom()
                        val existing = spaceVarBindings[exprAtom.name]
                        if (existing == null) {
                            spaceVarBindings[exprAtom.name] = patternSAtom
                        } else if (existing != patternSAtom) {
                            return false
                        }
                    } else if (exprAtom !is Symbol || exprAtom.name != patternAtom.name) {
                        return false
                    }
                }

                is Expression -> {
                    if (exprAtom is Variable) {
                        val patternSAtom = patternAtom.toSAtom()
                        val existing = spaceVarBindings[exprAtom.name]
                        if (existing == null) {
                            spaceVarBindings[exprAtom.name] = patternSAtom
                        } else if (existing != patternSAtom) {
                            return false
                        }
                    } else if (exprAtom !is Expression) {
                        return false
                    } else if (!matchAndCapture(patternAtom, exprAtom, storeIndex, newPath, bindings, space, spaceVarBindings)) {
                        return false
                    }
                }

                is Grounded<*> -> {
                    if (exprAtom is Variable) {
                        val patternSAtom = patternAtom.toSAtom()
                        val existing = spaceVarBindings[exprAtom.name]
                        if (existing == null) {
                            spaceVarBindings[exprAtom.name] = patternSAtom
                        } else if (existing != patternSAtom) {
                            return false
                        }
                    } else if (exprAtom !is Grounded<*> || exprAtom.value != patternAtom.value) {
                        return false
                    }
                }

                is Special -> {
                    // Special atoms (`:`, `=`, `@`, `->`) appear as plain data in
                    // space facts after the rewriter fallback (21bbee2) — e.g.
                    // `(:= (Green Sam) T)` parses to `(: = (Green Sam) T)`, a
                    // four-atom expression with two leading Specials. They must
                    // unify just like Symbols do.
                    if (exprAtom is Variable) {
                        val patternSAtom = patternAtom.toSAtom()
                        val existing = spaceVarBindings[exprAtom.name]
                        if (existing == null) {
                            spaceVarBindings[exprAtom.name] = patternSAtom
                        } else if (existing != patternSAtom) {
                            return false
                        }
                    } else if (exprAtom !is Special || exprAtom.value != patternAtom.value) {
                        return false
                    }
                }

                else -> return false
            }
        }

        return true
    }

    override fun match(): List<Bindings> {
        // Use existing methods to resolve packed index to Bindings
        val space = cachedSpace
            ?: throw IllegalStateException("Indexer must be indexed with a space before calling match()")

        return getPackedIndex().resolveAll(space)
    }

    fun getPackedIndex(): PackedIndex {
        return PackedIndex(schema, packedMatches.toList(), spaceVarSubstitutions.toList())
    }
}