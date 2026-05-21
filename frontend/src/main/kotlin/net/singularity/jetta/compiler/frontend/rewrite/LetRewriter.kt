package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*

/**
 * Desugars MeTTa's `let` binding form into a lambda application.
 *
 *  `(let $var $value $body)`  →  `((\ ($var) $body) $value)`
 *
 * Only Form 1 (variable-LHS) is handled here — the body becomes the lambda
 * body, the value the lambda argument, and the resolver / codegen pipeline
 * then treats it as any other lambda call. Variable type is inferred from
 * `$value`'s type via the existing type-inference logic.
 *
 * Form 2 (pattern-LHS, e.g. `(let (List $t) (get-type ...) $t)`) is left
 * untouched — it needs match-style unification, planned for a follow-up.
 *
 * Runs after [FunctionRewriter] and before [LambdaRewriter] so the synthesised
 * `(\ …)` form is then converted to a [Lambda] IR node.
 */
class LetRewriter : Rewriter {
    override fun rewrite(source: ParsedSource): ParsedSource {
        val result = source.code.map { atom ->
            if (atom is FunctionDefinition) {
                atom.copy(body = rewriteAtom(atom.body))
            } else atom
        }
        return ParsedSource(source.filename, result)
    }

    private fun rewriteAtom(atom: Atom): Atom = when (atom) {
        is Expression -> rewriteExpression(atom)
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
        if (expression.atoms.isEmpty()) return expression

        val head = expression.atoms[0]
        if (head is Symbol && head.name == LET_KEYWORD && expression.atoms.size == 4) {
            val lhs = expression.atoms[1]
            val rawValue = expression.atoms[2]
            val rawBody = expression.atoms[3]
            if (lhs is Variable) {
                val value = rewriteAtom(rawValue)
                val body = rewriteAtom(rawBody)
                val paramList = Expression(listOf(lhs), position = lhs.position)
                val lambdaForm = Expression(
                    listOf(Special(Predefined.LAMBDA), paramList, body),
                    position = expression.position,
                )
                return Expression(
                    listOf(lambdaForm, value),
                    position = expression.position,
                )
            }
            // Form 2: pattern-LHS. Fall through to generic rewrite — handled
            // by a future pass.
        }

        return Expression(
            expression.atoms.map { rewriteAtom(it) },
            position = expression.position,
        )
    }

    companion object {
        const val LET_KEYWORD = "let"
    }
}
