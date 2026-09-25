package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*

/**
 * Quotes only those whole function / match-branch bodies that the backend cannot
 * execute directly as code:
 *
 * 1. empty expression bodies: ()
 * 2. expression bodies whose head is itself an Expression
 *
 * This is intentionally very narrow. It exists to preserve symbolic return literals
 * such as `((mortal Plato) proven-by ((human Plato)))` without affecting normal
 * unresolved-call diagnostics, match templates, or recursive executable bodies.
 */
class QuotePureSymbolicBodiesRewriter : Rewriter {
    override fun rewrite(source: ParsedSource): ParsedSource {
        val result = source.code.map { atom ->
            val def = atom as FunctionDefinition
            def.copy(body = rewriteBody(def.body))
        }
        return ParsedSource(source.filename, result)
    }

    private fun rewriteBody(atom: Atom): Atom =
        when (atom) {
            is Match -> atom.copy(
                branches = atom.branches.map { branch ->
                    branch.copy(body = rewriteBranchBody(branch.body))
                }
            )
            else -> rewriteFunctionBody(atom)
        }

    private fun rewriteFunctionBody(atom: Atom): Atom =
        when (atom) {
            is Expression -> if (shouldQuoteWholeBody(atom)) quote(atom) else atom
            else -> atom
        }

    private fun rewriteBranchBody(atom: Atom): Atom =
        when (atom) {
            is Expression -> if (shouldQuoteWholeBody(atom)) quote(atom) else atom
            else -> atom
        }

    private fun shouldQuoteWholeBody(expression: Expression): Boolean {
        if (expression.type != GroundedType.ATOM) return false
        if (expression.atoms.isEmpty()) return true
        // Pure DATA only: a call anywhere inside is evaluated, as the reference evaluates the
        // subterms of a tuple — `(= (d) ((lol (f 42))))` answers `((lol 84))`, not the term.
        return expression.atoms[0] is Expression && !containsCall(expression)
    }

    private fun containsCall(atom: Atom): Boolean =
        when (atom) {
            is Lambda -> true
            is Expression -> atom.atoms.isNotEmpty() && atom.atoms[0] != PredefinedAtoms.QUOTE && (
                atom.resolved != null || atom.atoms[0] is Special || atom.atoms[0] is Lambda ||
                    atom.atoms.any { containsCall(it) })
            else -> false
        }

    private fun quote(expression: Expression): Expression =
        Expression(PredefinedAtoms.QUOTE, expression, position = expression.position)
}