package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression

interface Space {
    fun add(expression: Expression)

    fun mkIndex(patterns: List<Expression>)

    fun contains(id: Int): Boolean

    fun match(src: Expression, dst: Atom): List<Atom>

    fun chunks(numberOfChunks: Int): List<Iterator<Expression>>
}