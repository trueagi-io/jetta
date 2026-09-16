package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Predefined
import net.singularity.jetta.compiler.frontend.ir.PredefinedAtoms
import net.singularity.jetta.compiler.frontend.ir.Variable

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
        when (val value = JettaProgram.deref(value)) {
            is BoundAtom -> normalize(value.atom)
            is Grounded<*> -> value.value
            // A MeTTa boolean has several surface representations that must compare equal:
            // a `Grounded<Boolean>` (from a comparison / `and` / `or`), a raw java.lang.Boolean,
            // and the bare symbols `True`/`False` (e.g. a quoted expected `(True)`, or a symbol
            // a rule returned). Grounded already collapses to its raw Boolean above; collapse the
            // symbols the same way so `(assertEqualToResult (< …) (True))` and friends hold.
            is net.singularity.jetta.compiler.frontend.ir.Symbol -> when (value.name) {
                "True" -> true
                "False" -> false
                else -> value
            }
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

    /**
     * Multiset (bag) equality: order-insensitive, multiplicity-sensitive. This matches
     * Hyperon's `assertEqual`, whose @doc compares "(sets of) results" and whose
     * `_assert-results-are-equal` is built on `subtraction-atom` (which preserves
     * multiplicity). The non-deterministic order of results is not guaranteed, so an
     * ordered comparison would spuriously fail tests like `b4_nondeterm`
     * (`(match … (color) …)` yields `[green yellow red]` while `(superpose (red yellow
     * green))` yields `[red yellow green]` — same bag, different order).
     */
    private fun bagsEqual(a: List<Any?>, b: List<Any?>): Boolean =
        a.size == b.size && a.groupingBy { it }.eachCount() == b.groupingBy { it }.eachCount()

    @JvmStatic
    fun assertEqual(actual: Any?, expected: Any?) {
        // Every value is treated as a bag of results. A literal like `Plato` is the
        // singleton bag {Plato}; a multivalued call's List result is its full bag.
        //
        // Without this lift, `assertEqual [Plato] Plato` (multivalued actual, scalar
        // expected) would fail spuriously even though both sides denote the same
        // singleton bag. That mismatch is what surfaced as b2_backchain ASSERT_FAIL —
        // `(ift (deduce ...) $x)` builds a flat-map yielding `[Plato]`, but the
        // expected `Plato` is a scalar.
        val actualBag = normalizeActualResults(actual)
        val expectedBag = normalizeActualResults(expected)
        if (!bagsEqual(actualBag, expectedBag)) {
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
        if (!bagsEqual(normalizedActual, normalizedExpected)) {
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

    // --- the `_assert-results-are-*` family -----------------------------------------------

    /**
     * Alpha-equivalence: [a] and [b] are structurally equal up to a consistent RENAMING of their
     * variables, so `(Father $X)` and `(Father $Y)` are equivalent while `(Father $X)` and
     * `(Son $X)` are not. The renaming must be a BIJECTION, which is why both directions are
     * tracked: without [backward], `(f $a $b)` and `(f $c $c)` would pass, since `$a`->`$c` and
     * `$b`->`$c` are each consistent read one way.
     *
     * Shared by the `=alpha` builtin ([JettaProgram]) and the alpha half of this family; the
     * reference grounds both on the same `atoms_are_equivalent`.
     */
    internal fun alphaEquivalent(
        a: Atom,
        b: Atom,
        forward: MutableMap<String, String> = HashMap(),
        backward: MutableMap<String, String> = HashMap(),
    ): Boolean = when {
        a is Variable && b is Variable -> {
            val f = forward.putIfAbsent(a.name, b.name) ?: b.name
            val r = backward.putIfAbsent(b.name, a.name) ?: a.name
            f == b.name && r == a.name
        }
        a is Variable || b is Variable -> false
        a is Expression && b is Expression ->
            a.atoms.size == b.atoms.size &&
                a.atoms.indices.all { alphaEquivalent(a.atoms[it], b.atoms[it], forward, backward) }
        a is Expression || b is Expression -> false
        else -> a == b
    }

    private fun unwrap(value: Any?): Any? =
        when (val v = JettaProgram.deref(value)) {
            is BoundAtom -> unwrap(v.atom)
            else -> v
        }

    /**
     * The bag of results in an argument of the `_assert-results-are-*` family.
     *
     * The reference hands these a COLLAPSED TUPLE — an `Expression` whose children are the
     * results — and the library's MeTTa definitions always build one, through
     * `(metta (collapse $actual) %Undefined% $space)`. Through our runtime that pair answers
     * `Convert.collapse`'s single tuple wrapped in the usual multivalued result `List`, so the
     * argument arrives here as a one-element `List` holding the tuple. A `List` of any other
     * size is a bare result bag — nothing in the library produces one, but a direct call can —
     * and is taken as the bag itself.
     */
    private fun resultBag(value: Any?): List<Atom> {
        val v = unwrap(value)
        if (v is List<*>) {
            return if (v.size == 1) resultBag(v[0]) else v.mapNotNull { unwrap(it) as? Atom }
        }
        return when (v) {
            is Expression -> v.atoms
            is Atom -> listOf(v)
            else -> emptyList()
        }
    }

    /**
     * Multiset equality under an arbitrary [eq], mirroring the reference's `compare_vec_no_order`
     * over a `ListMap` keyed by the custom equality: each element joins the first bucket whose
     * representative it is [eq]-equal to, and the bags are equal when every bucket's two counts
     * agree. [bagsEqual]'s hash grouping cannot express this — alpha equivalence has no canonical
     * key to group by, because which renaming is the right one depends on what the atom is being
     * compared against.
     */
    private fun bagsEqualBy(
        actual: List<Atom>,
        expected: List<Atom>,
        eq: (Atom, Atom) -> Boolean,
    ): Boolean {
        if (actual.size != expected.size) return false
        val representatives = mutableListOf<Atom>()
        val counts = mutableListOf<IntArray>()
        fun bucketOf(atom: Atom): IntArray {
            val existing = representatives.indexOfFirst { eq(it, atom) }
            if (existing >= 0) return counts[existing]
            representatives.add(atom)
            return IntArray(2).also { counts.add(it) }
        }
        actual.forEach { bucketOf(it)[0]++ }
        expected.forEach { bucketOf(it)[1]++ }
        return counts.all { it[0] == it[1] }
    }

    /**
     * The shared body of the four `_assert-results-are-*` entry points: compare two result bags
     * and answer the unit atom, or fail.
     *
     * The reference answers `(Error <assert> <report>)` on a mismatch; we THROW, as [assertEqual]
     * and [assertEqualToResult] do. A failing assert that answers an inert error term is silent,
     * and a silent assert is exactly the false pass that hid `assertAlphaEqualToResult` from this
     * suite for as long as the library was not linked: with the library's definition missing, the
     * call was an unresolved head, i.e. data, and `!(assertAlphaEqualToResult (+ 1 2) (WRONG))`
     * succeeded. Throwing also keeps failures visible along the library route once our own
     * `assertEqual*` builtins stop shadowing the MeTTa definitions.
     */
    private fun compareResults(
        name: String,
        actual: Any?,
        expected: Any?,
        assert: Any?,
        message: Any?,
        eq: (Atom, Atom) -> Boolean,
    ): Atom {
        val actualBag = resultBag(actual)
        val expectedBag = resultBag(expected)
        if (!bagsEqualBy(actualBag, expectedBag, eq)) {
            throw AssertionError(
                buildString {
                    append(name)
                    append(" failed")
                    if (message != null) {
                        append("\n")
                        append(unwrap(message))
                    } else {
                        append("\nExpected: ")
                        append(expectedBag)
                        append("\nGot:      ")
                        append(actualBag)
                    }
                    append("\nIn:       ")
                    append(unwrap(assert))
                }
            )
        }
        return JettaProgram.UNIT_ATOM
    }

    /** Structural equality of two results, under the coercions [normalize] applies. */
    private fun resultsEqual(a: Atom, b: Atom): Boolean = normalize(a) == normalize(b)

    /** Alpha-equivalence of two results, with any bindings resolved first (as `=alpha` does). */
    private fun resultsAlphaEqual(a: Atom, b: Atom): Boolean =
        alphaEquivalent(Matcher.resolveDeep(a), Matcher.resolveDeep(b))

    /**
     * `_assert-results-are-equal <actual-results> <expected-results> <assert>` — the grounded
     * comparison hyperon's `stdlib.metta` builds every `assertEqual*` on. The library's MeTTa
     * definition collapses each side and then hands both bags plus the ORIGINAL assert term here,
     * for the error message. That term is the assert's own self-application
     * (`(assertEqual $actual $expected)`), which is why the third parameter is INERT in
     * `Externals`: reducing it re-enters the assert and never returns.
     */
    @JvmStatic
    fun `_assert-results-are-equal`(actual: Any?, expected: Any?, assert: Atom): Atom =
        compareResults("_assert-results-are-equal", actual, expected, assert, null, ::resultsEqual)

    /** [_assert-results-are-equal] with the caller's own failure message. */
    @JvmStatic
    fun `_assert-results-are-equal-msg`(
        actual: Any?,
        expected: Any?,
        assert: Atom,
        message: Atom,
    ): Atom =
        compareResults("_assert-results-are-equal-msg", actual, expected, assert, message, ::resultsEqual)

    /** [_assert-results-are-equal], comparing up to a renaming of variables. */
    @JvmStatic
    fun `_assert-results-are-alpha-equal`(actual: Any?, expected: Any?, assert: Atom): Atom =
        compareResults(
            "_assert-results-are-alpha-equal", actual, expected, assert, null, ::resultsAlphaEqual,
        )

    /** [_assert-results-are-alpha-equal] with the caller's own failure message. */
    @JvmStatic
    fun `_assert-results-are-alpha-equal-msg`(
        actual: Any?,
        expected: Any?,
        assert: Atom,
        message: Atom,
    ): Atom =
        compareResults(
            "_assert-results-are-alpha-equal-msg", actual, expected, assert, message,
            ::resultsAlphaEqual,
        )
}
