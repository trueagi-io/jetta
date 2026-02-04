package net.singularity.jetta.runtime.space

/**
 * Represents one match: variable assignments as PackedBindings.
 * Bindings are indexed by variable index from VariableSchema.
 */
class PackedMatch(
    val bindings: Array<PackedBinding>
) {
    fun getBinding(variableIndex: Int): PackedBinding = bindings[variableIndex]

    fun size(): Int = bindings.size

    override fun toString(): String {
        return "PackedMatch(bindings=${bindings.contentToString()})"
    }
}