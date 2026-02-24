package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.atoms.SAtom
import net.singularity.jetta.runtime.space.atoms.toSAtom
import java.util.concurrent.Executors
import java.util.concurrent.Future

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

        val k = 8
        val threadPool = Executors.newFixedThreadPool(k)

        try {
            val chunks = space.chunks(k)
            val chunkSize = (space.getStoreSize() + k - 1) / k

            val futures = mutableListOf<Future<List<Pair<PackedMatch, Map<String, SAtom>>>>>()

            chunks.forEachIndexed { chunkIndex, chunk ->
                val future = threadPool.submit<List<Pair<PackedMatch, Map<String, SAtom>>>> {
                    val chunkResults = mutableListOf<Pair<PackedMatch, Map<String, SAtom>>>()

                    var localIndex = 0
                    chunk.forEach { spaceExpr ->
                        val globalStoreIndex = chunkIndex * chunkSize + localIndex
                        val result = tryMatch(spaceExpr, globalStoreIndex, space)
                        if (result != null) {
                            chunkResults.add(result)
                        }
                        localIndex++
                    }

                    chunkResults
                }
                futures.add(future)
            }

            packedMatches.clear()
            spaceVarSubstitutions.clear()
            futures.forEach { future ->
                future.get().forEach { (match, subs) ->
                    packedMatches.add(match)
                    spaceVarSubstitutions.add(subs)
                }
            }

        } finally {
            threadPool.shutdown()
        }
    }

    private fun tryMatch(expr: Expression, storeIndex: Int, space: SpaceImpl): Pair<PackedMatch, Map<String, SAtom>>? {
        val bindings = Array<PackedBinding?>(schema.size()) { null }
        val spaceVarBindings = mutableMapOf<String, SAtom>()

        if (matchAndCapture(pattern, expr, storeIndex, intArrayOf(), bindings, space, spaceVarBindings)) {
            @Suppress("UNCHECKED_CAST")
            return PackedMatch(bindings as Array<PackedBinding>) to spaceVarBindings
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