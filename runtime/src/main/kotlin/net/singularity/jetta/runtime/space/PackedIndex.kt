package net.singularity.jetta.runtime.space

import net.singularity.jetta.runtime.space.atoms.SAtom

/**
 * Efficient storage for all matches of an indexed pattern.
 */
class PackedIndex(
    val schema: VariableSchema,
    private val matches: List<PackedMatch>
) {
    fun getMatch(index: Int): PackedMatch = matches[index]

    fun size(): Int = matches.size

    fun isEmpty(): Boolean = matches.isEmpty()

    /**
     * Resolve a match to actual Bindings by extracting atoms from Space.
     */
    fun resolve(matchIndex: Int, space: SpaceImpl): Bindings {
        val match = matches[matchIndex]
        val bindings = HashMapBindingsImpl()

        schema.variableNames.forEachIndexed { varIndex, varName ->
            val packed = match.getBinding(varIndex)
            val atom = space.extractAtom(packed)
            bindings[varName] = atom
        }

        return bindings
    }

    /**
     * Resolve all matches to Bindings list.
     */
    fun resolveAll(space: SpaceImpl): List<Bindings> {
        return (0 until size()).map { resolve(it, space) }
    }

    override fun toString(): String {
        return "PackedIndex(schema=$schema, matches=${matches.size})"
    }
}