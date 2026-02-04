package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.atoms.toSAtom
import java.util.concurrent.Executors
import java.util.concurrent.Future

class IndexerImpl(val pattern: Expression) : Indexer {

    private val schema: VariableSchema = VariableSchema.fromPattern(pattern)
    private val packedMatches = mutableListOf<PackedMatch>()
    private var cachedSpace: SpaceImpl? = null  // Cache the space for lazy resolution

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
                            val b = bindings[a.name]
                            if (b == null) {
                                bindings[a.name] = atom.toSAtom()
                            } else {
                                if (b != atom.toSAtom()) return false
                            }
                        }

                        else -> return false
                    }
                }

                is Expression -> {
                    val a = expr.atoms[it]
                    if (a !is Expression) return false
                    val indexer = IndexerImpl(atom)
                    if (!indexer.matchAndUnify(a, bindings)) return false
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
                    if (a !is Grounded<*>) return false
                    if (a.value != atom.value) return false
                }

                else -> return false
            }
        }
        return true
    }

    override fun index(space: Space, expr: Expression) {
        require(space is SpaceImpl) { "Space must be SpaceImpl for packed indexing" }

        // Cache space reference for later resolution
        cachedSpace = space

        val k = 8
        val threadPool = Executors.newFixedThreadPool(k)

        try {
            val chunks = space.chunks(k)
            val chunkSize = (space.getStoreSize() + k - 1) / k

            // Process each chunk in parallel
            val futures = mutableListOf<Future<List<PackedMatch>>>()

            chunks.forEachIndexed { chunkIndex, chunk ->
                val future = threadPool.submit<List<PackedMatch>> {
                    val chunkResults = mutableListOf<PackedMatch>()

                    var localIndex = 0
                    chunk.forEach { spaceExpr ->
                        val globalStoreIndex = chunkIndex * chunkSize + localIndex
                        val packedMatch = tryMatch(spaceExpr, globalStoreIndex, space)
                        if (packedMatch != null) {
                            chunkResults.add(packedMatch)
                        }
                        localIndex++
                    }

                    chunkResults
                }
                futures.add(future)
            }

            // Collect and merge all results
            packedMatches.clear()
            futures.forEach { future ->
                packedMatches.addAll(future.get())
            }

        } finally {
            threadPool.shutdown()
        }
    }

    private fun tryMatch(expr: Expression, storeIndex: Int, space: SpaceImpl): PackedMatch? {
        val bindings = Array<PackedBinding?>(schema.size()) { null }

        if (matchAndCapture(pattern, expr, storeIndex, intArrayOf(), bindings, space)) {
            @Suppress("UNCHECKED_CAST")
            return PackedMatch(bindings as Array<PackedBinding>)
        }

        return null
    }

    private fun matchAndCapture(
        patternExpr: Expression,
        expr: Expression,
        storeIndex: Int,
        currentPath: IntArray,
        bindings: Array<PackedBinding?>,
        space: SpaceImpl
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
                        // Variable appears multiple times - check if values are the same
                        val existingValue = space.extractAtom(existing)
                        val currentValue = space.extractAtom(current)
                        if (existingValue != currentValue) {
                            return false
                        }
                    }
                }

                is Symbol -> {
                    if (exprAtom !is Symbol || exprAtom.name != patternAtom.name) {
                        return false
                    }
                }

                is Expression -> {
                    if (exprAtom !is Expression) return false
                    if (!matchAndCapture(patternAtom, exprAtom, storeIndex, newPath, bindings, space)) {
                        return false
                    }
                }

                is Grounded<*> -> {
                    if (exprAtom !is Grounded<*> || exprAtom.value != patternAtom.value) {
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

    /**
     * Get the packed index containing all matches.
     */
    fun getPackedIndex(): PackedIndex {
        return PackedIndex(schema, packedMatches.toList())
    }
}