package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.ParsedSource

class CompositeRewriter : Rewriter {
    private val factories = mutableListOf<() -> Rewriter>()

    fun add(rewriter: () -> Rewriter) {
        factories.add(rewriter)
    }

    override fun rewrite(source: ParsedSource): ParsedSource =
        factories.fold(source) { acc, factory ->
            val rewriter = factory()
            rewriter.rewrite(acc)
        }
}