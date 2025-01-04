package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.Lambda

class ReplaceNodesRewriter(private val nodesToReplace: Map<Atom, Atom>) : Rewriter {
    override fun rewrite(source: ParsedSource): ParsedSource {
        val result = mutableListOf<Atom>()
        source.code.forEach { atom ->
            val def = atom as FunctionDefinition
            val expression = def.copy(body = rewriteAtom(def.body) as Expression)
            result.add(expression)
        }
        return ParsedSource(source.filename, result)
    }

    private fun rewriteAtom(atom: Atom): Atom =
        nodesToReplace[atom] ?: when (atom) {
            is Expression -> rewriteExpression(atom)
            is Lambda -> rewriteLambda(atom)
            else -> atom
        }

    private fun rewriteExpression(expression: Expression): Atom =
        expression.copy(atoms = expression.atoms.map(::rewriteAtom))

    private fun rewriteLambda(lambda: Lambda): Atom =
        lambda.copy(body = rewriteAtom(lambda.body))
}