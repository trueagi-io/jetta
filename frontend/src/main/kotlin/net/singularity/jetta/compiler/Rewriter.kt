package net.singularity.jetta.compiler

import net.singularity.jetta.compiler.frontend.ParsedSource

interface Rewriter {
    fun rewrite(source: ParsedSource): ParsedSource
}