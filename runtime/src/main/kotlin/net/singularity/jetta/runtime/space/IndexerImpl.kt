
package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.atoms.SGrounded
import net.singularity.jetta.runtime.space.atoms.toSAtom
import java.util.concurrent.Executors
import java.util.concurrent.Future

class IndexerImpl(val pattern: Expression) : Indexer {

    private val indexed = mutableListOf<Bindings>()

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
                                if (b != atom) return false
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
                    val a = expr.atoms[it].toSAtom()
                    if (a !is SGrounded<*>) return false
                    if (a != atom.toSAtom()) return false
                }

                else -> return false
            }
        }
        return true
    }

    override fun index(space: Space, expr: Expression) {
        val k = 8
        val threadPool = Executors.newFixedThreadPool(k)

        try {
            val chunks = space.chunks(k)

            // Process each chunk in parallel
            val futures = mutableListOf<Future<List<Bindings>>>()

            chunks.forEach { chunk ->
                val future = threadPool.submit<List<Bindings>> {
                    val chunkResults = mutableListOf<Bindings>()

                    chunk.forEach { spaceExpr ->
                        val bindings = HashMapBindingsImpl()
                        if (match(spaceExpr, bindings)) {
                            chunkResults.add(bindings)
                        }
                    }

                    chunkResults
                }
                futures.add(future)
            }

            // Collect and merge all results
            indexed.clear()
            futures.forEach { future ->
                indexed.addAll(future.get())
            }

        } finally {
            threadPool.shutdown()
        }
    }


    override fun match(): List<Bindings> {
        return indexed.toList()
    }
}