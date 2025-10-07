package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.runtime.space.Space

object Matcher {
    fun match(space: Space, src: Expression, dst: Atom): List<Expression> =
        space.match(src, dst)
}