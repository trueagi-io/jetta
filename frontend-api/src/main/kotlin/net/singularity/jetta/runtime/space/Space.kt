package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression

interface Space {
    fun add(expression: Expression)

    /**
     * Add [expression] as an atom an `import!` COPIED in, rather than one this space owns.
     *
     * The distinction exists because the reference interpreter does not copy at all: it keeps an
     * imported module in its own space and delegates queries into it, so a program's `get-atoms`
     * answers the program's atoms and nothing else — on a one-fact program with the standard
     * library loaded, `(get-atoms &self)` is that one fact. Our `import!` copies, which is why
     * the two have to be told apart here. Every other query keeps reading the whole space, which
     * is the reference's behaviour too: a reflective `match &self` over the library's `:`
     * declarations does find them there.
     */
    fun addImported(expression: Expression)

    /**
     * Remove the first stored atom structurally equal to [expression]. Returns whether one
     * was found and removed. Backs the `remove-atom` built-in.
     */
    fun remove(expression: Expression): Boolean

    /** Snapshot of every atom currently in the space, imported ones included. */
    fun getAtoms(): List<Expression>

    /**
     * Snapshot of the atoms this space OWNS — [getAtoms] without what an `import!` copied in.
     * Backs the `get-atoms` built-in; see [addImported].
     */
    fun getOwnAtoms(): List<Expression>

    fun mkIndex(patterns: List<Expression>)

    fun contains(id: Int): Boolean

    fun match(src: Expression, dst: Atom): List<Atom>

    fun chunks(numberOfChunks: Int): List<Iterator<Expression>>
}
