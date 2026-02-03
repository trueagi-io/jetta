package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Expression

interface Indexer {
    fun match(expr: Expression, bindings: Bindings): Boolean

    fun index(space: Space, expr: Expression)

    fun match(): List<Bindings>
}