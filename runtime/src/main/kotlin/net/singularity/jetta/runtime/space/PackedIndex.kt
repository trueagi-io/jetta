package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.atoms.SAtom
import net.singularity.jetta.runtime.space.atoms.SExpression
import net.singularity.jetta.runtime.space.atoms.SVariable
import net.singularity.jetta.runtime.space.atoms.toAtom

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
            // An unbound slot (see PackedMatch) contributes NO entry: the variable stays free,
            // and substitution leaves a variable it finds no binding for as itself.
            val packed = match.getBinding(varIndex) ?: return@forEachIndexed
            val atom = space.extractAtom(packed)
            bindings[varName] = if (subs.isNotEmpty()) applySubstitutions(atom, subs) else atom
        }

        return bindings
    }

    /**
     * Atom-valued counterpart of [resolve]: extract each variable's value as the raw store
     * [net.singularity.jetta.compiler.frontend.ir.Atom] with NO SAtom round-trip. Space-var
     * substitutions (rare on the backward-chaining fast path) still convert only the small
     * substituted RHS, and only when present; with empty subs the extracted atom is returned
     * untouched (zero allocation).
     */
    fun resolveToAtoms(matchIndex: Int, space: SpaceImpl): HashMap<String, Atom> {
        val match = matches[matchIndex]
        val subs = getSpaceVarSubstitutions(matchIndex)
        val out = HashMap<String, Atom>()
        schema.variableNames.forEachIndexed { varIndex, varName ->
            // See [resolve]: an unbound slot contributes no entry.
            val packed = match.getBinding(varIndex) ?: return@forEachIndexed
            val atom = space.extractAtomRaw(packed)
            out[varName] = if (subs.isNotEmpty()) applySubstitutionsAtom(atom, subs) else atom
        }
        return out
    }

    private fun applySubstitutionsAtom(atom: Atom, subs: Map<String, SAtom>): Atom = when (atom) {
        is Variable -> subs[atom.name]?.toAtom() ?: atom
        is Expression -> Expression(
            atoms = atom.atoms.map { applySubstitutionsAtom(it, subs) },
            type = atom.type,
            resolved = atom.resolved
        )
        else -> atom
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