package net.singularity.jetta.runtime.space

/**
 * Points to a specific atom within a stored expression.
 *
 * Example:
 *   Expression at store[5] = (foo bar baz)
 *   PackedBinding(storeIndex=5, atomPath=[1]) -> "bar"
 */
data class PackedBinding(
    val storeIndex: Int,
    val atomPath: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PackedBinding) return false
        return storeIndex == other.storeIndex && atomPath.contentEquals(other.atomPath)
    }

    override fun hashCode(): Int {
        return 31 * storeIndex + atomPath.contentHashCode()
    }

    override fun toString(): String {
        return "PackedBinding(storeIndex=$storeIndex, atomPath=${atomPath.contentToString()})"
    }
}