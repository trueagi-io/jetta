
package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.atoms.toAtom

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
        // Get or create indexer for this pattern
        val indexer = indexers.getOrPut(src) {
            IndexerImpl(src).also {
                it.index(this, src)
            }
        }

        // Get all matching bindings
        val matchingBindings = indexer.match()

        // For each matching binding, substitute variables in dst with their bindings
        return matchingBindings.map { bindings ->
            substituteVariables(dst, bindings)
        }
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

    companion object {
        private val instance = SpaceImpl()

        @JvmStatic
        fun getInstance(): Space {
            return instance
        }
    }
}