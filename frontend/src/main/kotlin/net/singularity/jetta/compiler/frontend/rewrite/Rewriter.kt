package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.ParsedSource

interface Rewriter {
    fun rewrite(source: ParsedSource): ParsedSource
}