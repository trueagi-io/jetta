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
}
