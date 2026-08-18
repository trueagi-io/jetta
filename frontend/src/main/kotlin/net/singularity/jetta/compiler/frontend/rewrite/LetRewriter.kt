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
 * Form 2 (pattern-LHS, e.g. `(let (List $t) (get-type ...) $t)`) is lowered onto the
 * runtime `letMatch` helper, which unifies the pattern against each result and applies a
 * lambda over the pattern's variables.
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
                    sourceOrdinal = branch.sourceOrdinal,
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
        // `let*` — sequential bindings. `(let* (($v1 e1) ($v2 e2) …) body)` desugars to
        // right-nested `let`s, `(let $v1 e1 (let $v2 e2 … body))`, so each later binding sees
        // the earlier ones. The nested `let`s are then rewritten to lambdas by the branch below.
        if (head is Symbol && head.name == LETSTAR_KEYWORD && expression.atoms.size == 3) {
            val bindings = expression.atoms[1]
            if (bindings is Expression) {
                var acc = expression.atoms[2]
                for (pair in bindings.atoms.reversed()) {
                    if (pair is Expression && pair.atoms.size == 2) {
                        acc = Expression(
                            listOf(Symbol(LET_KEYWORD), pair.atoms[0], pair.atoms[1], acc),
                            position = expression.position,
                        )
                    }
                }
                return rewriteAtom(acc)
            }
        }
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
            // Form 2 — a `(quote $v)` pattern LHS: `(let (quote $v) VAL BODY)` binds `$v` to the
            // CONTENT of VAL's quote (VAL evaluates to `(quote X)` ⇒ `$v = X`). Lower to Form-1
            // over the runtime `unquote` helper: `(let $v (unquote VAL) BODY)`, then let the
            // variable-LHS branch above build the lambda. This is what `render` relies on to
            // peel and recompose quoted sub-programs. (General structural patterns still fall
            // through — a unify-based follow-up shared with `case`.)
            if (lhs is Expression && lhs.atoms.size == 2 && isQuoteHead(lhs.atoms[0]) &&
                lhs.atoms[1] is Variable
            ) {
                val v = lhs.atoms[1] as Variable
                val unquoted = Expression(
                    listOf(Symbol(UNQUOTE_KEYWORD), rawValue),
                    position = rawValue.position,
                )
                return rewriteAtom(
                    Expression(
                        listOf(Symbol(LET_KEYWORD), v, unquoted, rawBody),
                        position = expression.position,
                    )
                )
            }
            // Form 2 — a general STRUCTURAL pattern LHS, e.g. `(let (List $t) VAL BODY)`. The
            // pattern's variables are bound by unifying it against each result of VAL; BODY is
            // then evaluated with those bindings. Lowered to the runtime `letMatch` helper plus a
            // lambda over the pattern variables (document order): `(letMatch (List $t) VAL
            // (\ ($t) BODY))`. `letMatch` unifies, reads the bindings back, and applies the
            // lambda per result.
            //
            // ANY number of pattern variables lowers. This used to be capped at one, which left
            // `(let ($head $tail) (decons-atom $x) …)` — the reference stdlib's dominant shape,
            // and the whole point of `decons-atom` — falling through to the generic rewrite as an
            // INERT term: the body was still evaluated (with the pattern variables free, so a
            // `unify $head …` inside it "succeeded" against an unbound variable) and the `let`
            // itself survived into the result as data. `letMatch` already binds N variables
            // positionally in the same document order [collectPatternVars] produces here.
            //
            // DIVERGENCE, deliberate: only the PATTERN's variables are bound. A variable on the
            // VALUE side that unification also binds — `(let ($a $b 3) (1 2 $c) ($a $b $c))`,
            // where `$c` unifies with `3` — stays free in BODY (corpus `letlet.metta`). Closing
            // it needs the parameter names threaded from here as data, the way `unify` does
            // (see JettaProgram.unifyMatch): the runtime cannot collect them from the value,
            // because by then an in-scope variable has become a VALUE that may itself contain
            // variables, and picking those up would shift every positional argument. The
            // reference stdlib always passes a ground value (`(decons-atom $x)`), so nothing
            // there depends on it.
            if (lhs is Expression) {
                val vars = collectPatternVars(lhs)
                if (vars.isNotEmpty()) {
                    val value = rewriteAtom(rawValue)
                    val body = rewriteAtom(rawBody)
                    val paramList = Expression(vars.map { Variable(it) }, position = lhs.position)
                    val lambda = Expression(
                        listOf(Special(Predefined.LAMBDA), paramList, body),
                        position = expression.position,
                    )
                    return Expression(
                        listOf(Symbol(LETMATCH_KEYWORD), lhs, value, lambda),
                        position = expression.position,
                    )
                }
            }
            // Other pattern-LHS forms: fall through to generic rewrite — a unify-based follow-up.
        }

        return Expression(
            expression.atoms.map { rewriteAtom(it) },
            position = expression.position,
        )
    }

    private fun isQuoteHead(head: Atom): Boolean =
        (head as? Symbol)?.name == "quote" || (head as? Special)?.value == "quote"

    /** Variable names occurring in [pattern], in document order, deduplicated. */
    private fun collectPatternVars(pattern: Atom): List<String> {
        val out = LinkedHashSet<String>()
        fun go(a: Atom) {
            when (a) {
                is Variable -> out.add(a.name)
                is Expression -> a.atoms.forEach(::go)
                else -> {}
            }
        }
        go(pattern)
        return out.toList()
    }

    companion object {
        const val LET_KEYWORD = "let"
        const val LETSTAR_KEYWORD = "let*"
        const val UNQUOTE_KEYWORD = "unquote"
        const val LETMATCH_KEYWORD = "letMatch"
    }
}
