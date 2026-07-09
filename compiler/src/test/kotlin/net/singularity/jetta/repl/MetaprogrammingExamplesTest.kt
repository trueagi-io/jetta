package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Metaprogramming via JIT-eval, all verified end to end through the REPL.
 *
 * The thesis JeTTa bets on — "the compiler is part of the runtime" — pays off here:
 * `eval` takes a MeTTa expression as DATA and reduces it by COMPILING it to JVM
 * bytecode at call time through the real compiler, loading it, and running it. So code
 * is data, data is runnable, and eval'd code shares the running program's environment:
 * it links straight to the program's already-compiled functions and can reflectively
 * query its rule base.
 *
 * (These run in same-JVM mode — the REPL / in-process. Cross-JVM `jettac`+`jetta`
 * support for eval'ing user functions awaits the persisted-environment `.jctx` slice;
 * pure system-expression evals like example 1 / 5 work there already.)
 */
class MetaprogrammingExamplesTest {

    private fun repl(): Repl = ReplImpl()
    private fun String.dollars() = replace('_', '$')

    /** 1. Code is data. A quoted expression is inert; `eval` JIT-compiles and runs it. */
    @Test
    fun `code as data`() {
        repl().eval("""!(eval '(+ 1 2))""").let {
            assertTrue(it.isSuccess)
            assertEquals("[3]", it.result.toString())
        }
    }

    /** 2. eval links to YOUR compiled functions — `double` is a real compiled method;
     *  the eval'd `(double 21)` lowers to an INVOKESTATIC against it, not a re-interpret. */
    @Test
    fun `eval calls a compiled user function`() {
        val r = repl()
        r.eval(
            """
            (: double (-> Int Int))
            (= (double _x) (* _x 2))
            """.trimIndent().dollars()
        )
        r.eval("""!(eval '(double 21))""").let {
            assertTrue(it.isSuccess)
            assertEquals("[42]", it.result.toString())
        }
    }

    /** 3. First-class code: a function RETURNS a program (a quoted expression), and
     *  `eval` runs whatever it returns. `snippet` yields `(+ 10 20)`; eval makes it 30. */
    @Test
    fun `a function returns code and eval runs it`() {
        val r = repl()
        r.eval(
            """
            (: snippet (-> Atom))
            (= (snippet) '(+ 10 20))
            """.trimIndent()
        )
        r.eval("""!(eval (snippet))""").let {
            assertTrue(it.isSuccess)
            assertEquals("[30]", it.result.toString())
        }
    }

    /** 4. Reflection: eval'd code queries the live rule base with `match &self`, reading
     *  back the DEFINITION of `color` (its rule body) as data. */
    @Test
    fun `eval reflectively reads a rule from the space`() {
        val r = repl()
        r.eval(
            """
            (@ color multivalued)
            (: color (-> Atom))
            (= (color) (superpose (red yellow green)))
            """.trimIndent()
        )
        r.eval("""!(eval '(match &self (= (color) _x) _x))""".dollars()).let {
            assertTrue(it.isSuccess)
            assertEquals("[(superpose (red yellow green))]", it.result.toString())
        }
    }

    /** 5. Meta-circular: eval evaluating an eval. The inner `'(+ 1 2)` stays data until
     *  the inner eval compiles and runs it. */
    @Test
    fun `eval of eval`() {
        repl().eval("""!(eval '(eval '(+ 1 2)))""").let {
            assertTrue(it.isSuccess)
            assertEquals("[3]", it.result.toString())
        }
    }

    /** 6. Variable capture into quoted code: `$x` inside `'(+ $x 1)` is captured at the
     *  value it is bound to, so `(bump 41)` builds `(+ 41 1)` and evals it to 42. */
    @Test
    fun `eval captures a bound variable`() {
        val r = repl()
        r.eval(
            """
            (: bump (-> Int Atom))
            (= (bump _x) (eval '(+ _x 1)))
            """.trimIndent().dollars()
        )
        r.eval("""!(bump 41)""".dollars()).let {
            assertTrue(it.isSuccess)
            assertEquals("[42]", it.result.toString())
        }
    }
}
