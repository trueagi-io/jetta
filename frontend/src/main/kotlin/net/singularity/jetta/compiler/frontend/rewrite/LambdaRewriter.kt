package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.rewrite.messages.ExpectVariableButFoundMessage

class LambdaRewriter(private val messageCollector: MessageCollector) : Rewriter {
    override fun rewrite(source: ParsedSource): ParsedSource {
        val result = mutableListOf<Atom>()
        source.code.forEach { atom ->
            val def = atom as FunctionDefinition
            val expression = def.copy(body = rewriteAtom(def.body))
            result.add(expression)
        }
        return ParsedSource(source.filename, result)
    }

    private fun rewriteAtom(atom: Atom): Atom =
        when (atom) {
            is Expression -> rewriteExpression(atom)
            // Descend into Match branches — a multi-clause function is lowered to a Match, and a
            // `let` (→ `(\ …)`) inside a clause body lives in a branch. Without this the `\`
            // survives to the resolver (TODO "atom=\") for any multi-clause / recursive function
            // that uses `let`. Mirrors LetRewriter, which already recurses through Match.
            is Match -> Match(
                branches = atom.branches.map { branch ->
                    MatchBranch(
                        cond = branch.cond?.let { rewriteAtom(it) as Expression },
                        body = rewriteAtom(branch.body),
                        destructuredBindings = branch.destructuredBindings,
                    )
                },
                returnType = atom.returnType,
                position = atom.position,
            )
            else -> atom
        }

    private fun rewriteExpression(expression: Expression): Atom {
        if (expression.atoms.isEmpty()) {
            return expression
        }
        return when ((expression.atoms.first() as? Special)?.value) {
            Predefined.LAMBDA -> {
                val (params, body) = expression.atoms.drop(1)
                Lambda(
                    extractFormalParams(params as Expression),
                    null,
                    rewriteAtom(body),
                    expression.position
                )
            }
            else -> {
                val atoms = expression.atoms.map {
                    rewriteAtom(it)
                }
                Expression(atoms, position = expression.position)
            }
        }
    }

    private fun extractFormalParams(expression: Expression): List<Variable> {
        val list = expression.atoms.mapNotNull {
            if (it is Variable) {
                it
            } else {
                messageCollector.add(ExpectVariableButFoundMessage(expression))
                null
            }
        }
        if (list.size != expression.atoms.size) throw RewriteException(expression)
        return list
    }
}