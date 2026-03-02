package net.singularity.jetta.compiler.frontend.ir

/**
 * A foliation leaf: an atom carrying its per-match-result binding snapshot.
 * Each match result wraps the substituted atom along with the variable
 * bindings that produced it, so that downstream consumers (flat-map, map)
 * can restore the correct bindings per iteration.
 */
class BoundAtom(val atom: Atom, val bindings: Map<String, Atom>) : Atom {
    override var type: Atom? get() = atom.type; set(v) { atom.type = v }
    override val position: SourcePosition? get() = atom.position
    override val id: Int get() = atom.id
    override fun toString(): String = atom.toString()
}