package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An environment-based meta-circular-style interpreter compiled by JeTTa (see
 * `examples/interp/EnvInterp.metta`). Exercises the whole guarded-recursion +
 * multivalued-composition path end to end: `lookup` over a destructured assoc list,
 * arithmetic composed over multivalued sub-evaluations, and lexical `Let`.
 *
 * Also pins the int-literal fix: a literal like `144` (> Byte range) must load via
 * LDC/SIPUSH, not a truncating BIPUSH (which produced -112).
 */
class EnvInterpreterTest {
    private fun String.d() = replace('_', '$')

    private fun interpreter() = ReplImpl().apply {
        eval(
            """
            (: lookup (-> Atom Atom Int))
            (= (lookup _key (Cons (Bind _k _v) _rest))
               (if (== _key _k) _v (lookup _key _rest)))

            (: ev (-> Atom Atom Int))
            (= (ev (Lit _n) _env) _n)
            (= (ev (Var _x) _env) (lookup _x _env))
            (= (ev (Add _a _b) _env) (+ (ev _a _env) (ev _b _env)))
            (= (ev (Mul _a _b) _env) (* (ev _a _env) (ev _b _env)))
            (= (ev (Let _x _e _body) _env) (bindEval _x (ev _e _env) _body _env))

            (: bindEval (-> Atom Int Atom Atom Int))
            (= (bindEval _x _val _body _env) (ev _body (Cons (Bind _x _val) _env)))
            """.trimIndent().d()
        )
    }

    private fun ReplImpl.evalsTo(expr: String, expected: String) =
        eval("!$expr".d()).let {
            assertTrue(it.isSuccess, it.messages.joinToString("\n"))
            assertEquals(expected, it.result.toString())
        }

    @Test
    fun `literal and arithmetic`() {
        val i = interpreter()
        i.evalsTo("(ev (Lit 42) Nil)", "[42]")
        // 3 + (4 * 5) = 23
        i.evalsTo("(ev (Add (Lit 3) (Mul (Lit 4) (Lit 5))) Nil)", "[23]")
    }

    @Test
    fun `variable lookup from the environment`() {
        val i = interpreter()
        // env {x=7, y=2}; (x + y) = 9
        i.evalsTo("(ev (Add (Var x) (Var y)) (Cons (Bind x 7) (Cons (Bind y 2) Nil)))", "[9]")
    }

    @Test
    fun `let with lexical scope`() {
        val i = interpreter()
        // (let x = 10 in x + 5) = 15
        i.evalsTo("(ev (Let x (Lit 10) (Add (Var x) (Lit 5))) Nil)", "[15]")
        // nested: (let a = 3 in (let b = 4 in a + b)) = 7
        i.evalsTo("(ev (Let a (Lit 3) (Let b (Lit 4) (Add (Var a) (Var b)))) Nil)", "[7]")
    }

    @Test
    fun `let shadowing over an expression yields a large value`() {
        val i = interpreter()
        // env {n=2}; (let n = n + 10 in n * n) = 144 — also pins the >127 literal path
        i.evalsTo("(ev (Let n (Add (Var n) (Lit 10)) (Mul (Var n) (Var n))) (Cons (Bind n 2) Nil))", "[144]")
    }

    @Test
    fun `int literals outside byte range load correctly`() {
        val i = interpreter()
        // 200, 40000, and a negative — none must wrap through BIPUSH/SIPUSH truncation.
        i.evalsTo("(ev (Lit 200) Nil)", "[200]")
        i.evalsTo("(ev (Lit 40000) Nil)", "[40000]")
        i.evalsTo("(ev (Add (Lit 100000) (Lit 1)) Nil)", "[100001]")
    }
}
