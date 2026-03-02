package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.runtime.Matcher
import net.singularity.jetta.runtime.space.atoms.SAtom
import net.singularity.jetta.runtime.space.atoms.toAtom
import net.singularity.jetta.runtime.space.atoms.toSAtom

class SpaceImpl : Space {
    private val store = mutableListOf<Expression>()
    private val indexers = mutableMapOf<Expression, IndexerImpl>()


    override fun add(expression: Expression) {
        store.add(expression)
    }

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

        // Get or create indexer for this pattern
        val indexer = indexers.getOrPut(src) {
            IndexerImpl(src).also {
                it.index(this, src)
            }
        }

        // Get packed index
        val packedIndex = indexer.getPackedIndex()

        println("[MATCH] pattern=$src template=$dst matches=${packedIndex.size()}")

        return (0 until packedIndex.size()).map { matchIndex ->
            val bindings = packedIndex.resolve(matchIndex, this)
            val result = substituteVariables(dst, bindings)
            val spaceVarSubs = packedIndex.getSpaceVarSubstitutions(matchIndex)
            val final = applySpaceVarSubstitutions(result, spaceVarSubs)
            // Capture per-result bindings as a foliation leaf
            val snapshot = mutableMapOf<String, Atom>()
            collectBindings(src, bindings, snapshot)
            writeBindingsToVariables(src, bindings)
            println("[MATCH]   result[$matchIndex] = $final (spaceVarSubs=$spaceVarSubs)")
            BoundAtom(final, snapshot)
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
                bindings[atom.name]!!.toAtom()
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

    companion object {
        private val instance = SpaceImpl()

        @JvmStatic
        fun getInstance(): Space {
            return instance
        }
    }
}