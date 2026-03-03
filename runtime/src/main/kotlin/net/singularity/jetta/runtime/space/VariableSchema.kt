package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Variable

/**
 * Maps variable names to indices for compact storage.
 * Variables are stored in sorted order for consistency.
 */
data class VariableSchema(
    val variableNames: List<String>
) {
    fun getIndex(name: String): Int = variableNames.indexOf(name)

    fun getName(index: Int): String = variableNames[index]

    fun size(): Int = variableNames.size

    companion object {
        fun fromPattern(pattern: Expression): VariableSchema {
            val variables = mutableSetOf<String>()

            fun visit(expr: Expression) {
                expr.atoms.forEach { atom ->
                    when (atom) {
                        is Variable -> variables.add(atom.name)
                        is Expression -> visit(atom)
                        else -> {}
                    }
                }
            }

            visit(pattern)
            return VariableSchema(variables.sorted())
        }
    }
}