package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.ParsedSource

class CompositeRewriter : Rewriter {
    private val rewriters = mutableListOf<Rewriter>()

    fun add(rewriter: Rewriter) {
        rewriters.add(rewriter)
    }

    override fun rewrite(source: ParsedSource): ParsedSource =
        rewriters.fold(source) { acc, rewriter ->
            rewriter.rewrite(acc)
        }
}