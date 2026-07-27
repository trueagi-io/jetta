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
        // D2.4 (increment 4): an unresolved-head application whose argument is a reducible
        // grounded type error (`(f (+ 5 "S"))`, f declared but unruled) is NOT pure symbolic
        // data — quoting it verbatim would freeze the inner `(+ 5 "S")`. Leave it live so codegen
        // reduces the argument and surfaces its `(Error …)` (errors are absorbing in hyperon).
        // Only the ACTUAL is checked here; the expected is quoted data (may legitimately contain a
        // literal `(+ 5 "S")` inside its `(Error …)`), so this never corrupts expected data.
        if (hasReducibleGroundedError(actual)) return expression

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

    /**
     * Whether a direct argument of [expression] is a statically-known grounded type error — a
     * `+`/`-`/`*` or `==`/`!=` sub-expression stamped `ATOM` (by the D2.2/D2.4 resolver) with a
     * `String` operand. Mirrors the backend's `firstStaticArgError`: such an argument reduces to
     * an `(Error …)` that must surface, so the enclosing application is not pure symbolic data.
     */
    private fun hasReducibleGroundedError(expression: Expression): Boolean =
        expression.atoms.drop(1).any { arg ->
            arg is Expression && groundedOpName(arg.atoms.firstOrNull()) in GROUNDED_ERROR_OPS &&
                arg.atoms.drop(1).any { it.type == GroundedType.STRING }
        }

    /** The surface name of a grounded-op head, whether stored as [Special] or [Symbol]. */
    private fun groundedOpName(head: Atom?): String? = when (head) {
        is Special -> head.value
        is Symbol -> head.name
        else -> null
    }

    companion object {
        private val GROUNDED_ERROR_OPS = setOf(
            Predefined.PLUS, Predefined.MINUS, Predefined.TIMES,
            Predefined.COND_EQ, Predefined.COND_NEQ,
        )
    }
}