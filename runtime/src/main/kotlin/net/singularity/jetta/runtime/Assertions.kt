package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Predefined
import net.singularity.jetta.compiler.frontend.ir.PredefinedAtoms

object Assertions {
    /**
     * Comparison key for a free [net.singularity.jetta.compiler.frontend.ir.Variable].
     * `Variable` uses identity equality, but two variables that print the same (e.g.
     * the `$n` on each side of `(assertEqual (Add $n Z) $n)`) denote the same variable
     * and must compare equal. Keying by name gives that structural equality without
     * touching `Variable`'s global identity semantics (which `match`/`Matcher` rely on).
     */
    private data class VarKey(val name: String)

    /**
     * Comparison key for a normalized [Expression]. Distinct from a plain [List] so
     * [normalizeActualResults] does not mistake a single expression result for a
     * multivalued bag of results — a bare `List` means "bag", an `ExprKey` means
     * "one expression whose children have been normalized".
     */
    private data class ExprKey(val atoms: List<Any?>)

    private fun normalize(value: Any?): Any? =
        when (value) {
            is BoundAtom -> normalize(value.atom)
            is Grounded<*> -> value.value
            is net.singularity.jetta.compiler.frontend.ir.Variable -> VarKey(value.name)
            // Recurse so nested variables inside an expression are compared by name too
            // (e.g. `(S $n)` vs `(S $n)`). Symbols already compare by name; grounded
            // leaves collapse to their values.
            is Expression -> ExprKey(value.atoms.map { normalize(it) })
            is List<*> -> value.map { normalize(it) }
            else -> value
        }

    private fun normalizeActualResults(actual: Any?): List<Any?> =
        when (val normalized = normalize(actual)) {
            is List<*> -> normalized.map { normalize(it) }
            null -> emptyList()
            else -> listOf(normalized)
        }

    private fun unquote(atom: Any?): Any? =
        if (atom is Expression && atom.atoms.size == 2 && atom.atoms[0] == PredefinedAtoms.QUOTE) {
            atom.atoms[1]
        } else {
            atom
        }

    private fun decodeExpectedResults(expected: Any?): List<Any?> {
        val syntax = unquote(expected)
        return when (syntax) {
            is Expression -> decodeExpectedResultsExpression(syntax)
            null -> emptyList()
            else -> listOf(normalize(syntax))
        }
    }

    private fun decodeExpectedResultsExpression(expression: Expression): List<Any?> {
        if (expression.atoms.isEmpty()) return emptyList()

        val head = expression.atoms[0]
        if (head == PredefinedAtoms.QUOTE && expression.atoms.size == 2) {
            return listOf(normalize(expression.atoms[1]))
        }

        if (head is net.singularity.jetta.compiler.frontend.ir.Special &&
            head.value == Predefined.SEQ
        ) {
            return expression.atoms.drop(1).map { normalize(unquote(it)) }
        }

        return expression.atoms.map { normalize(unquote(it)) }
    }

    @JvmStatic
    fun assertEqual(actual: Any?, expected: Any?) {
        // Hyperon-style bag semantics: every value is treated as a bag of results.
        // A literal like `Plato` is the singleton bag {Plato}; a multivalued call's
        // List result is its full bag. Two bags are equal iff they match as ordered
        // lists (order- and multiplicity-sensitive — Hyperon's `assertEqual` is the
        // same; set semantics would require sort+dedupe).
        //
        // Without this lift, `assertEqual [Plato] Plato` (multivalued actual, scalar
        // expected) would fail spuriously even though both sides denote the same
        // singleton bag. That mismatch is what surfaced as b2_backchain ASSERT_FAIL —
        // `(ift (deduce ...) $x)` builds a flat-map yielding `[Plato]`, but the
        // expected `Plato` is a scalar.
        val actualBag = normalizeActualResults(actual)
        val expectedBag = normalizeActualResults(expected)
        if (actualBag != expectedBag) {
            throw AssertionError(
                buildString {
                    append("assertEqual failed")
                    append("\nExpected: ")
                    append(normalize(expected))
                    append("\nActual:   ")
                    append(normalize(actual))
                }
            )
        }
    }

    @JvmStatic
    fun assertEqualToResult(actual: Any?, expected: Any?) {
        val normalizedActual = normalizeActualResults(actual)
        val normalizedExpected = decodeExpectedResults(expected)
        if (normalizedActual != normalizedExpected) {
            throw AssertionError(
                buildString {
                    append("assertEqualToResult failed")
                    append("\nExpected results: ")
                    append(normalizedExpected)
                    append("\nActual results:   ")
                    append(normalizedActual)
                }
            )
        }
    }
}