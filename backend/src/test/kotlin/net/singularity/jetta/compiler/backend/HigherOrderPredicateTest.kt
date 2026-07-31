package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression tests for the f1_imports VerifyError chain — a higher-order predicate that walks an
 * Expression. All three defects shared one root cause: codegen guessed the JVM stack shape from a
 * node's resolved type instead of the shape the producer actually left (see [stackShapeType]).
 *
 * A `contains`-style predicate exercises each fix at once:
 *  1. `(if ($condition $head) …)` — a higher-order `(-> Atom Bool)` call used as a condition. Its
 *     result is unboxed to a primitive by generateLambdaCall, but the call node resolved to `Any`;
 *     `pushValueAsCondition` must re-box off the real stack shape before `isTruthy(Object)`.
 *  2. `(contains $tail $condition)` — `$tail` is bound from `cdr-atom : (-> Atom Atom)` (slot `Atom`)
 *     yet the recursive call's parameter is `Expression`; generateLoadVar must downcast the widened
 *     `Atom` slot to `Expression`.
 *  3. `contains` returns `Bool` but its body is a `let`-lambda typed `Any` (an Object on the stack);
 *     coerceForReturn must coerce that reference to the primitive via `isTruthy`.
 *  4. the predicate passed in is MULTIVALUED — its body calls a bag-returning function though it is
 *     declared `(-> … Bool)`. Passing it by name eta-expands it into a lambda whose emitted method
 *     returns primitive `Z`, so the body's `List` has to be coerced; the node's scalar element type
 *     hid that a reference was on the stack.
 *
 * Each program asserts via `!(assertEqual …)`; a wrong answer throws AssertionError from `__main`,
 * so a green `invoke` IS the assertion.
 */
class HigherOrderPredicateTest : GeneratorTestBase() {

    private fun run(name: String, code: String) {
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach(::println)
                assertTrue(messageCollector.list().isEmpty())
                val classes = result.toMap().toClasses()
                JettaProgram.init(name)
                classes[name]!!.getMethod("__main").invoke(null)
            }
    }

    private val containsDef = """
        (: contains (-> Expression (-> Atom Bool) Bool))
        (= (contains ${'$'}list ${'$'}condition)
          (if (== ${'$'}list ()) False
            (let ${'$'}head (car-atom ${'$'}list)
              (if (${'$'}condition ${'$'}head) True
                (let ${'$'}tail (cdr-atom ${'$'}list) (contains ${'$'}tail ${'$'}condition)) ))))
        (: is-3 (-> Atom Bool))
        (= (is-3 ${'$'}x) (== ${'$'}x 3))
    """.trimIndent()

    /**
     * The predicate matches an element deep in the expression — drives the full recursion through
     * the higher-order condition, the Atom→Expression tail-arg downcast, and the Bool-from-Any
     * return. Before the fixes this VerifyError'd at class load in three successive spots.
     */
    @Test
    fun `higher-order predicate finds a matching element`() = run(
        "HoContainsTrue",
        """
            $containsDef
            !(assertEqual (contains (1 2 3 4) is-3) True)
        """.trimIndent()
    )

    /** No element matches — the recursion bottoms out at `()` and returns False. */
    @Test
    fun `higher-order predicate returns false when nothing matches`() = run(
        "HoContainsFalse",
        """
            $containsDef
            !(assertEqual (contains (1 2 4) is-3) False)
        """.trimIndent()
    )

    /** Empty expression — the base case returns False without ever calling the condition. */
    @Test
    fun `higher-order predicate on empty expression is false`() = run(
        "HoContainsEmpty",
        """
            $containsDef
            !(assertEqual (contains () is-3) False)
        """.trimIndent()
    )

    // --- a MULTIVALUED predicate in a Bool-typed function slot --------------------------------

    /**
     * `is-one` is declared `(-> Number Bool)` but its body compares against a bag-returning call,
     * so it is multivalued and really returns a `List`. Passing it BY NAME eta-expands it into a
     * lambda whose method returns primitive `Z` — the `List` has to be coerced there, and a
     * one-element bag in a boolean slot is its element.
     */
    private val multivaluedPredicateDef = $$"""
        (: one (-> Number))
        (= (one) (superpose (1)))
        (: is-one (-> Number Bool))
        (= (is-one $x) (== $x (one)))
        (: holds (-> Number (-> Number Bool) Bool))
        (= (holds $a $c) (if ($c $a) True False))
    """.trimIndent()

    @Test
    fun `a multivalued predicate passed by name holds`() = run(
        "HoMultivaluedTrue",
        """
            $multivaluedPredicateDef
            !(assertEqual (holds 1 is-one) True)
        """.trimIndent()
    )

    @Test
    fun `a multivalued predicate passed by name fails`() = run(
        "HoMultivaluedFalse",
        """
            $multivaluedPredicateDef
            !(assertEqual (holds 2 is-one) False)
        """.trimIndent()
    )
}
