package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression

interface Space {
    fun put(expression: Expression)

    fun mkIndex(patterns: List<Expression>)

    fun contains(id: Int): Boolean

    fun match(src: Expression, dst: Atom): List<Expression>
}