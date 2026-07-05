package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression

interface Space {
    fun add(expression: Expression)

    /**
     * Remove the first stored atom structurally equal to [expression]. Returns whether one
     * was found and removed. Backs the `remove-atom` built-in.
     */
    fun remove(expression: Expression): Boolean

    /** Snapshot of every atom currently in the space. Backs the `get-atoms` built-in. */
    fun getAtoms(): List<Expression>

    fun mkIndex(patterns: List<Expression>)

    fun contains(id: Int): Boolean

    fun match(src: Expression, dst: Atom): List<Atom>

    fun chunks(numberOfChunks: Int): List<Iterator<Expression>>
}
