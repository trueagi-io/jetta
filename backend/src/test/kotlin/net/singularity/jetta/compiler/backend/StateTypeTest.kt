package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Typing of mutable states (e2_states). A state is `(StateMonad T)` where `T` is the type of its
 * content, from hyperon's stdlib signatures
 *
 *     (: new-state     (-> $t (StateMonad $t)))
 *     (: get-state     (-> (StateMonad $t) $t))
 *     (: change-state! (-> (StateMonad $t) $t (StateMonad $t)))
 *
 * which also make `change-state!` reject a value of a different type than the state was created
 * with. Each program asserts via `!(assertEqual …)`; a wrong answer throws AssertionError from
 * `__main`, so a green `invoke` IS the assertion.
 */
class StateTypeTest : GeneratorTestBase() {

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

    /** The content type flows through `new-state` into the state's own type. */
    @Test
    fun `a state is typed by its content`() = run(
        "StateTypeNew",
        """
            !(assertEqual (get-type (new-state 2)) (StateMonad Number))
            !(assertEqual (get-type (change-state! (new-state "S") "V")) (StateMonad String))
        """.trimIndent()
    )

    /** The same through a `let`-bound state — the binding does not erase the type. */
    @Test
    fun `a let-bound state keeps its type`() = run(
        "StateTypeLet",
        $$"""
            !(assertEqual (let $v (new-state 1) (get-type $v)) (StateMonad Number))
        """.trimIndent()
    )

    /**
     * A `:` fact can declare the type of a whole EXPRESSION, not only of a leaf, so a state
     * created from `(A B)` is `(StateMonad PairAB)` rather than ill-typed.
     */
    @Test
    fun `a declared compound type reaches the state that holds it`() = run(
        "StateTypeDeclared",
        """
            (: (A B) PairAB)
            !(bind! &tok (new-state (A B)))
            !(assertEqual (get-type &tok) (StateMonad PairAB))
        """.trimIndent()
    )

    /**
     * Writing a value of another type is an error, the state keeps its content, and the error
     * echoes the application AS WRITTEN — including the unreduced `(new-state 1)`, which is why
     * the check runs over the surface form rather than the reduced arguments.
     */
    @Test
    fun `a state write of the wrong type is a BadArgType error`() = run(
        "StateTypeBadWrite",
        """
            (: (A B) PairAB)
            !(bind! &tok (new-state (A B)))
            !(assertEqual
               (change-state! &tok 1)
               (Error (change-state! &tok 1) (BadArgType 2 PairAB Number)))
            !(assertEqual (get-state &tok) (A B))
            !(assertEqual
               (change-state! (new-state 1) "S")
               (Error (change-state! (new-state 1) "S") (BadArgType 2 Number String)))
        """.trimIndent()
    )

    /**
     * The written value is REDUCED before it is stored. With it kept as data, a value mentioning
     * the state itself (`(+ (get-state $x) 1)`) made the cell contain itself, and printing or
     * comparing it recursed forever.
     */
    @Test
    fun `the written value is reduced before it is stored`() = run(
        "StateTypeReducedWrite",
        $$"""
            !(assertEqual
               (let $x (new-state 1) (change-state! $x (+ (get-state $x) 1)))
               (new-state 2))
        """.trimIndent()
    )
}
