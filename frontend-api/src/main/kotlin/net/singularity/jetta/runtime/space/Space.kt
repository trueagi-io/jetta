package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression

interface Space {
    fun add(expression: Expression)

    /**
     * Delegate this space's queries into [space] as well — what `import!` does, instead of
     * copying the module in.
     *
     * This is the reference interpreter's model: an imported module stays in a space of its own
     * and the importing space reads through to it. So the library's atoms answer a reflective
     * `match &self` and type `:` lookups, while [getOwnAtoms] — the `get-atoms` builtin — still
     * answers only what the program itself declared, and `remove-atom` cannot delete a fact the
     * program does not own.
     *
     * Delegation is transitive (a module that imports another is read through as well),
     * deduplicated by identity (the diamond A→B,C→D reads D once) and cycle-safe.
     */
    fun addDelegate(space: Space)

    /**
     * Remove the first stored atom structurally equal to [expression]. Returns whether one
     * was found and removed. Backs the `remove-atom` built-in.
     */
    fun remove(expression: Expression): Boolean

    /** Snapshot of every atom this space can see — its own, then those it delegates to. */
    fun getAtoms(): List<Expression>

    /**
     * Snapshot of the atoms this space OWNS — [getAtoms] without what it reads through an
     * `import!`. Backs the `get-atoms` built-in; see [addDelegate].
     */
    fun getOwnAtoms(): List<Expression>

    fun mkIndex(patterns: List<Expression>)

    fun contains(id: Int): Boolean

    fun match(src: Expression, dst: Atom): List<Atom>

    fun chunks(numberOfChunks: Int): List<Iterator<Expression>>
}
