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
            val expression = def.copy(body = rewriteAtom(def.body) as Expression)
            result.add(expression)
        }
        return ParsedSource(source.filename, result)
    }

    private fun rewriteAtom(atom: Atom): Atom =
        when (atom) {
            is Expression -> rewriteExpression(atom)
            else -> atom
        }

    private fun rewriteExpression(expression: Expression): Atom =
        when (expression.atoms.first()) {
            Predefined.LAMBDA -> {
                val (params, body) = expression.atoms.drop(1)
                Lambda(
                    extractFormalParams(params as Expression),
                    null,
                    body as Expression,
                    expression.position
                )
            }
            else -> {
                val params = expression.atoms.drop(1).map {
                    rewriteAtom(it)
                }
                Expression(listOf(expression.atoms.first()) + params, position = expression.position)
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
        if (list.size != expression.atoms.size) throw RewriteException()
        return list
    }
}