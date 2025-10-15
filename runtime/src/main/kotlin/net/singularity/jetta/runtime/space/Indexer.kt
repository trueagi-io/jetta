package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Expression

interface Indexer {
    fun match(expr: Expression): Boolean

    fun index(expr: Expression)
}