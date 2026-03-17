package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*

/**
 * Post-resolve assertion-specific lowering.
 *
 * Rules:
 * - assertEqualToResult(actual, expected):
 *     expected is already quoted by FunctionRewriter
 *     actual is quoted here only if it is symbolic data
 *
 * - assertEqual(actual, expected):
 *     expected is quoted here only if it is symbolic data
 */
class LowerAssertExpressionsRewriter : Rewriter {
    override fun rewrite(source: ParsedSource): ParsedSource {
        val result = source.code.map { atom ->
            val def = atom as FunctionDefinition
            def.copy(body = rewriteAtom(def.body))
        }
        return ParsedSource(source.filename, result)
    }

    private fun rewriteAtom(atom: Atom): Atom =
        when (atom) {
            is Expression -> rewriteExpression(atom)
            is Lambda -> atom.copy(body = rewriteAtom(atom.body))
            is Match -> atom.copy(
                branches = atom.branches.map { branch ->
                    branch.copy(
                        cond = branch.cond?.let { rewriteAtom(it) as Expression },
                        body = rewriteAtom(branch.body)
                    )
                }
            )
            else -> atom
        }

    private fun rewriteExpression(expression: Expression): Atom {
        val rewrittenAtoms = expression.atoms.map { rewriteAtom(it) }
        val rewritten = expression.copy(atoms = rewrittenAtoms)

        val head = rewritten.atoms.firstOrNull() as? Symbol ?: return rewritten
        if (rewritten.atoms.size != 3) return rewritten

        return when (head.name) {
            "assertEqualToResult" -> lowerAssertEqualToResult(rewritten)
            "assertEqual" -> lowerAssertEqual(rewritten)
            else -> rewritten
        }
    }

    private fun lowerAssertEqualToResult(expression: Expression): Expression {
        val actual = expression.atoms[1]
        val expected = expression.atoms[2]

        val expectedQuoted =
            expected is Expression && expected.atoms.firstOrNull() == PredefinedAtoms.QUOTE
        val actualAlreadyQuoted =
            actual is Expression && actual.atoms.firstOrNull() == PredefinedAtoms.QUOTE

        if (!expectedQuoted || actualAlreadyQuoted) return expression
        if (actual !is Expression) return expression
        if (!shouldQuoteAsSymbolicData(actual)) return expression

        return expression.copy(
            atoms = listOf(
                expression.atoms[0],
                Expression(PredefinedAtoms.QUOTE, actual, position = actual.position),
                expected
            )
        )
    }

    private fun lowerAssertEqual(expression: Expression): Expression {
        val actual = expression.atoms[1]
        val expected = expression.atoms[2]

        val expectedAlreadyQuoted =
            expected is Expression && expected.atoms.firstOrNull() == PredefinedAtoms.QUOTE

        if (expectedAlreadyQuoted) return expression
        if (expected !is Expression) return expression
        if (!shouldQuoteAsSymbolicData(expected)) return expression

        return expression.copy(
            atoms = listOf(
                expression.atoms[0],
                actual,
                Expression(PredefinedAtoms.QUOTE, expected, position = expected.position)
            )
        )
    }

    private fun shouldQuoteAsSymbolicData(expression: Expression): Boolean {
        if (expression.atoms.isEmpty()) return true

        return when (val head = expression.atoms[0]) {
            is Expression -> true
            is Symbol -> expression.resolved == null
            else -> false
        }
    }
}