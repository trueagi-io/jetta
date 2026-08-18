package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression tests for the e2_states ArrayStoreException (B1). A `&`-prefixed name is lowered
 * to its bare String (the space-reference convention in codegen), so a state token returned
 * from a function arrives at `get-state`/`change-state!` as a `java.lang.String`, not a Symbol.
 * `cellOf` used to handle only Symbol/BoundAtom, so it fell through to `nonReducedState`, which
 * stored the String into an `Expression`'s `Atom[]` → ArrayStoreException. `cellOf` now resolves
 * a String token through the `tokens` registry (where `bind!` keyed it by name), and
 * `nonReducedState` wraps any non-Atom token before building the Expression.
 *
 * Each program asserts via `!(assertEqual …)`; a wrong answer throws AssertionError from
 * `__main`, so a green `invoke` IS the assertion.
 */
class StateTokenTest : GeneratorTestBase() {

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

    /**
     * A state token bound with `bind!` and returned indirectly from a function reaches
     * `get-state` as the bare `&`-name String. Before B1 this ArrayStoreException'd; now the
     * String resolves through the token registry to the cell's value.
     */
    @Test
    fun `get-state resolves a string-lowered token returned from a function`() = run(
        "StateTokenGet",
        $$"""
            !(bind! &tok (new-state 5))
            (= (get-tok) &tok)
            !(assertEqual (get-state (get-tok)) 5)
        """.trimIndent()
    )

    /** change-state! through the same string-lowered indirection updates the cell in place. */
    @Test
    fun `change-state! updates a string-lowered token`() = run(
        "StateTokenChange",
        $$"""
            !(bind! &cell (new-state 1))
            (= (ref) &cell)
            !(change-state! (ref) 2)
            !(assertEqual (get-state (ref)) 2)
        """.trimIndent()
    )

    // --- e3_match_states: a state addressed by a RUNTIME-installed rule ---------------------

    /**
     * The hyperon idiom of addressing a state through a symbolic expression rather than a token:
     * `add-atom` installs `(= (status (Goal lunch-order)) <cell>)` while the program runs, so
     * `status` is not a rule head codegen ever saw and `(status (Goal lunch-order))` reaches
     * `get-state` as inert data. `cellOf` reduces it against the live space.
     */
    @Test
    fun `get-state reduces an expression addressed by a runtime-installed rule`() = run(
        "StateIndirectGet",
        $$"""
            (= (new-goal-status! $goal $status)
                (let $new-state (new-state $status)
                     (add-atom &self (= (status (Goal $goal)) $new-state))))
            !(new-goal-status! lunch-order inactive)
            !(assertEqual (get-state (status (Goal lunch-order))) inactive)
        """.trimIndent()
    )

    /** The same indirection is writable: `change-state!` mutates the cell the rule names. */
    @Test
    fun `change-state! writes through a runtime-installed rule`() = run(
        "StateIndirectChange",
        $$"""
            (= (new-goal-status! $goal $status)
                (let $new-state (new-state $status)
                     (add-atom &self (= (status (Goal $goal)) $new-state))))
            !(new-goal-status! lunch-order inactive)
            !(nop (change-state! (status (Goal lunch-order)) active))
            !(assertEqual (get-state (status (Goal lunch-order))) active)
        """.trimIndent()
    )

    /** `nop` runs its argument for the effect and yields the unit atom `()`. */
    @Test
    fun `nop runs its argument and returns unit`() = run(
        "StateNop",
        $$"""
            !(bind! &c (new-state 1))
            !(assertEqual (nop (change-state! &c 2)) ())
            !(assertEqual (get-state &c) 2)
        """.trimIndent()
    )

    /**
     * A `bind!` token inside a match PATTERN denotes the atom it names — hyperon substitutes
     * tokens at parse time, JeTTa at match time. Combined with value-based state equality, a
     * pattern carrying one state atom finds the space atom carrying a different cell with the
     * same content.
     */
    @Test
    fun `a bound token inside a match pattern denotes its atom`() = run(
        "StateTokenInPattern",
        $$"""
            (= (new-goal-status! $goal $status)
                (let $new-state (new-state $status)
                     (add-atom &self (= (status (Goal $goal)) $new-state))))
            !(new-goal-status! lunch-order inactive)
            !(new-goal-status! meditation inactive)
            !(nop (change-state! (status (Goal lunch-order)) active))
            !(bind! &state-active (new-state active))
            !(nop (change-state! &state-active inactive))
            !(assertEqual
                (match &self (= (status (Goal $goal)) &state-active) $goal)
                meditation)
        """.trimIndent()
    )

    /** Two distinct cells holding equal values are equal states (hyperon's documented rule). */
    @Test
    fun `states with equal content are equal atoms`() = run(
        "StateValueEquality",
        $$"""
            !(bind! &tok (new-state (A B)))
            (= (get-token) &tok)
            !(assertEqual (get-token) (new-state (A B)))
        """.trimIndent()
    )

    /**
     * A free variable in a VALUE position resolves against the bindings an earlier sub-expression
     * installed: reducing the `if` condition binds `$goal`, and the then-branch must yield that
     * value rather than a free `$goal`.
     */
    @Test
    fun `a free variable in a value position sees a binding made by the condition`() = run(
        "StateFreeVarValue",
        $$"""
            (= (new-goal-status! $goal $status)
                (let $new-state (new-state $status)
                     (add-atom &self (= (status (Goal $goal)) $new-state))))
            !(new-goal-status! lunch-order inactive)
            !(nop (change-state! (status (Goal lunch-order)) active))
            !(assertEqual
                (if (== (get-state (status (Goal $goal))) active) $goal (superpose ()))
                lunch-order)
        """.trimIndent()
    )
}
