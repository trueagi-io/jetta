package net.singularity.jetta.runtime.space

/**
 * Represents one match: variable assignments as PackedBindings.
 * Bindings are indexed by variable index from VariableSchema.
 *
 * A slot is NULL when the pattern's variable is genuinely UNBOUND by this match — which
 * happens whenever a pattern sub-term unifies with a VARIABLE stored in the space: the
 * store variable is bound to the pattern sub-term (recorded as a space-var substitution,
 * see [PackedIndex.getSpaceVarSubstitutions]), so every variable inside that sub-term stays
 * free and no store position holds its value. `(bar $y)` in the space queried by
 * `(bar (f $z))` binds `$y := (f $z)` and leaves `$z` unbound. A null is therefore a normal
 * outcome, not a partially-built match: consumers must leave such a variable out of the
 * bindings they produce, so substitution keeps it as itself.
 */
class PackedMatch(
    val bindings: Array<PackedBinding?>
) {
    fun getBinding(variableIndex: Int): PackedBinding? = bindings[variableIndex]

    /**
     * The store position this match was captured from, or -1 when the match binds no
     * variable at all and so points at no fact (a ground pattern, or one whose every
     * variable sits under a store variable). All bindings of a match share one storeIndex —
     * a match always comes from a single stored atom.
     */
    fun storeIndexOrNull(): Int = bindings.firstOrNull { it != null }?.storeIndex ?: -1

    fun size(): Int = bindings.size

    override fun toString(): String {
        return "PackedMatch(bindings=${bindings.contentToString()})"
    }
}