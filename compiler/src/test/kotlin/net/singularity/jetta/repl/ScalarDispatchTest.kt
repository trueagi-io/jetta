package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scalar dispatch for deterministic destructuring functions. A function with mutually
 * exclusive clauses and a grounded-VALUE return (here the meta-circular interpreter `ev`,
 * `-> Int`) compiles to single-valued dispatch instead of the `List` machinery, so:
 *
 *  - its result is a scalar value, not a singleton bag (`15`, not `[15]`) — matching
 *    hyperon/PeTTa;
 *  - it composes directly in arithmetic — `(+ 100 (ev …))` — which a multivalued `ev`
 *    (returning a `List`) could not do (arithmetic-over-multivalued VerifyError);
 *  - a reducible call nested in a data constructor argument is evaluated applicatively —
 *    the `Let` clause `(ev $body (Cons (Bind $x (ev $e $env)) $env))` stores the VALUE of
 *    `(ev $e $env)` in the environment, not an inert thunk.
 *
 * This is what lets the symbolic interpreter benchmark run at scale as compiled code.
 */
class ScalarDispatchTest {
    private fun String.d() = replace('_', '$')

    private fun interpreter() = ReplImpl().also {
        assertTrue(
            it.eval(
                """
                (: lookup (-> Atom Atom Int))
                (= (lookup _key (Cons (Bind _k _v) _rest))
                   (if (== _key _k) _v (lookup _key _rest)))
                (: ev (-> Atom Atom Int))
                (= (ev (Lit _n) _env) _n)
                (= (ev (Var _x) _env) (lookup _x _env))
                (= (ev (Add _a _b) _env) (+ (ev _a _env) (ev _b _env)))
                (= (ev (Mul _a _b) _env) (* (ev _a _env) (ev _b _env)))
                (= (ev (Let _x _e _body) _env) (ev _body (Cons (Bind _x (ev _e _env)) _env)))
                """.trimIndent().d()
            ).isSuccess
        )
    }

    @Test
    fun `deterministic interpreter returns a scalar value`() {
        interpreter().eval("!(ev (Add (Lit 3) (Add (Var x) (Lit 5))) (Cons (Bind x 4) Nil))".d()).let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals("12", it.result.toString()) // 3 + (4 + 5); a bare value, not [12]
        }
    }

    @Test
    fun `scalar interpreter composes directly in arithmetic`() {
        // Only possible because ev is single-valued: a multivalued ev would hand `+` a List.
        interpreter().eval("!(+ 100 (ev (Add (Lit 3) (Lit 4)) Nil))".d()).let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals(107, it.result)
        }
    }

    @Test
    fun `let evaluates the bound expression into the environment (applicative order)`() {
        interpreter().eval(
            "!(ev (Let n (Add (Var n) (Lit 10)) (Mul (Var n) (Var n))) (Cons (Bind n 2) Nil))".d()
        ).let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals("144", it.result.toString()) // n := (2+10); n*n = 144 — value, not thunk
        }
    }
}
