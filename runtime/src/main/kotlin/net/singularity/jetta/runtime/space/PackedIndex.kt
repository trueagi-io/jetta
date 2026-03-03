package net.singularity.jetta.runtime.space

import net.singularity.jetta.runtime.space.atoms.SAtom
import net.singularity.jetta.runtime.space.atoms.SExpression
import net.singularity.jetta.runtime.space.atoms.SVariable

/**
 * Efficient storage for all matches of an indexed pattern.
 */
class PackedIndex(
    val schema: VariableSchema,
    private val matches: List<PackedMatch>,
    private val spaceVarSubstitutions: List<Map<String, SAtom>> = emptyList()
) {
    fun getSpaceVarSubstitutions(matchIndex: Int): Map<String, SAtom> =
        spaceVarSubstitutions.getOrElse(matchIndex) { emptyMap() }

    fun getMatch(index: Int): PackedMatch = matches[index]

    fun size(): Int = matches.size

    fun isEmpty(): Boolean = matches.isEmpty()

    /**
     * Resolve a match to actual Bindings by extracting atoms from Space.
     * Applies space-variable substitutions to extracted values.
     */
    fun resolve(matchIndex: Int, space: SpaceImpl): Bindings {
        val match = matches[matchIndex]
        val bindings = HashMapBindingsImpl()
        val subs = getSpaceVarSubstitutions(matchIndex)

        schema.variableNames.forEachIndexed { varIndex, varName ->
            val packed = match.getBinding(varIndex)
            val atom = space.extractAtom(packed)
            bindings[varName] = if (subs.isNotEmpty()) applySubstitutions(atom, subs) else atom
        }

        return bindings
    }

    private fun applySubstitutions(atom: SAtom, subs: Map<String, SAtom>): SAtom {
        return when (atom) {
            is SVariable -> subs[atom.name] ?: atom
            is SExpression -> SExpression(atom.atoms.map { applySubstitutions(it, subs) })
            else -> atom
        }
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