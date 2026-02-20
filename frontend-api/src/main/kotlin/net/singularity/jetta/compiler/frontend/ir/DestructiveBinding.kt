package net.singularity.jetta.compiler.frontend.ir

/**
 * Represents a variable that is extracted from a nested pattern.
 *
 * @param originalName The original variable name in the source (e.g., "a")
 * @param syntheticName The renamed variable name used in the body (e.g., "destr_0_1")
 * @param paramIndex The index of the formal parameter this variable is extracted from
 * @param extractionPath The sequence of atom indices to navigate into the expression
 */
data class DestructureBinding(
    val originalName: String,
    val syntheticName: String,
    val paramIndex: Int,
    val extractionPath: IntArray
) {
    constructor(originalName: String, paramIndex: Int, extractionPath: IntArray)
            : this(originalName, "destr_${paramIndex}_${extractionPath.joinToString("_")}", paramIndex, extractionPath)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DestructureBinding) return false
        return originalName == other.originalName &&
                syntheticName == other.syntheticName &&
                paramIndex == other.paramIndex &&
                extractionPath.contentEquals(other.extractionPath)
    }

    override fun hashCode(): Int {
        var result = originalName.hashCode()
        result = 31 * result + syntheticName.hashCode()
        result = 31 * result + paramIndex
        result = 31 * result + extractionPath.contentHashCode()
        return result
    }

    override fun toString(): String =
        $$"DestructureBinding($$originalName/$$syntheticName <- $var$$paramIndex$${extractionPath.joinToString("") { "[$it]" }})"
}