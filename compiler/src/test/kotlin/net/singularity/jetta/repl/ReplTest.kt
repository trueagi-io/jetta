package net.singularity.jetta.repl

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplTest {
    @Test
    fun simple() {
        val repl = createRepl()
        repl.eval("""
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y 1))
            !(foo 1 2)
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
            assertTrue(it.messages.isEmpty())
            assertEquals(4, it.result)
        }
    }

    @Test
    fun preserveReplContext() {
        val repl = createRepl()
        repl.eval("""
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y 1))
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
        }
        repl.eval("""
            (= (bar _x) (foo _x 2))
            !(bar 2)
        """.trimIndent().replace('_', '$')).let {
            it.messages.forEach { println(it) }
            assertTrue(it.isSuccess)
            assertEquals(5, it.result)
        }
    }

    @Test
    fun evalExpression() {
        val repl = createRepl()
        repl.eval("""
            !(+ 1 2)
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
            assertEquals(3, it.result)
        }
    }

    @Test
    fun expressionBeforeDefinition() {
        val repl = createRepl()
        repl.eval("""
            !(+ 1 2)
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
        }
        repl.eval("""
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y 1))
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
        }
    }

    @Test
    fun redefine() {
        val repl = createRepl()
        repl.eval("""
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y 1))
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
        }
        repl.eval("""
            (= (bar _x) (foo _x 2))
            !(bar 2)
        """.trimIndent().replace('_', '$')).let {
            it.messages.forEach { println(it) }
            assertTrue(it.isSuccess)
        }
        repl.eval("""
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y 2))
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
        }
        repl.eval("""
            !(bar 2)
        """.trimIndent()).let {
            assertTrue(it.isSuccess)
            assertEquals(5, it.result)
        }
        repl.eval("""
            !(foo 1 2)
        """.trimIndent()).let {
            assertTrue(it.isSuccess)
            assertEquals(5, it.result)
        }
    }

    @Test
    fun separateNamespaces() {
        val repl1 = createRepl()
        val repl2 = createRepl()
        repl1.eval("""
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y 1))
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
        }
        repl2.eval("""
            (: foo (-> Int Int))
            (= (foo _x) (+ _x _x))
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
        }
        repl1.eval("""
            !(foo 1 2)
        """.trimIndent()).let {
            assertTrue(it.isSuccess)
            assertEquals(4, it.result)
        }
        repl2.eval("""
            !(foo 2)
        """.trimIndent()).let {
            assertTrue(it.isSuccess)
            assertEquals(4, it.result)
        }
    }

    @Test
    fun lambdas() {
        val repl = createRepl()
        repl.eval("""
            (: foo (-> Int Int (-> Int Int Int) Int))
            (= (foo _x _y _f) (_f _x _y))
            !(foo 10 20 (\ (_x _y) (+ _x _y)))
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
            assertTrue(it.messages.isEmpty())
            assertEquals(30, it.result)
        }
    }

    @Test
    fun passFunction() {
        val repl = createRepl()
        repl.eval("""
            (: foo (-> Int Int (-> Int Int Int) Int))
            (= (foo _x _y _f) (_f _x _y))
            (: bar (-> Int Int Int))
            (= (bar _x _y) (+ _x _y))
            !(foo 10 20 bar)
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
            assertTrue(it.messages.isEmpty())
            assertEquals(30, it.result)
        }
    }

    @Test
    fun nonDeterministicFunction() {
        val repl = createRepl()
        repl.eval("""
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (: f (-> Int Int))
            (= (f _x) (+ _x 1))
            
            (@ bar multivalued)
            (: bar (-> Int))
            (= (bar) (f (foo)))
           
            !(bar)
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
            assertTrue(it.messages.isEmpty())
            assertEquals(listOf(2, 3, 4), it.result)
        }
    }

    @Test
    fun nonDeterministicExpression() {
        val repl = createRepl()
        repl.eval("""
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (: f (-> Int Int))
            (= (f _x) (+ _x 1))
            
            (@ bar multivalued)
            (: bar (-> Int))
            (= (bar) (f (foo)))
           
            !(+ 1 (bar))
        """.trimIndent().replace('_', '$')).let {
            it.messages.forEach {
                println(it)
            }
            assertTrue(it.isSuccess)
            assertTrue(it.messages.isEmpty())
            assertEquals(listOf(3, 4, 5), it.result)
        }
    }

    @Test
    fun `recover from a failure`() {
        val repl = createRepl()
        repl.eval("""!(+ 1 (foo 2))""").let {
            assertFalse(it.isSuccess)
        }
        repl.eval(
            """
            (: log-int (-> Int Int))
            (= (log-int _x)
                (if (== _x 1) 0 (+ 1 (log-int (- _x 1))))
            )
            !(log-int 8)
            """.trimIndent().replace('_', '$')
        ).let {
            assertTrue(it.isSuccess)
            println(it.result)
        }
    }

    @Test
    fun `split function definition and the call`() {
        val repl = createRepl()
        repl.eval("""
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
        """.trimIndent()).let {
            assertTrue(it.isSuccess)
        }
        repl.eval("""
            !(foo)
        """.trimIndent()).let {
            assertTrue(it.isSuccess)
        }
    }

    @Test
    fun `jit-eval of a quoted arithmetic expression`() {
        val repl = createRepl()
        // `'(+ 1 2)` is inert data (the tick reader macro); `eval` JIT-compiles and runs
        // it at call time via JettaJit. Result is the multivalued bag [3].
        repl.eval("""!(eval '(+ 1 2))""").let {
            it.messages.forEach(::println)
            assertTrue(it.isSuccess)
            assertEquals("[3]", it.result.toString())
        }
    }

    @Test
    fun `jit-eval of a quoted multivalued superpose`() {
        val repl = createRepl()
        repl.eval("""!(eval '(superpose (red yellow green)))""").let {
            it.messages.forEach(::println)
            assertTrue(it.isSuccess)
            assertEquals("[red, yellow, green]", it.result.toString())
        }
    }

    @Test
    fun `jit-eval links against a compiled user function`() {
        val repl = createRepl()
        // Define a multivalued user function, compiled to a real static method.
        repl.eval("""
            (@ color multivalued)
            (: color (-> Atom))
            (= (color) (superpose (red yellow green)))
        """.trimIndent()).let { assertTrue(it.isSuccess) }
        // `(eval '(color))` forks the live AOT Context, resolves `color` to its compiled
        // class, and emits INVOKESTATIC against it (links, not recompiles) — the bag
        // comes back from the already-compiled function.
        repl.eval("""!(eval '(color))""").let {
            it.messages.forEach(::println)
            assertTrue(it.isSuccess)
            assertEquals("[red, yellow, green]", it.result.toString())
        }
    }

    @Test
    fun `runtime match self finds a rule in the live space`() {
        val repl = createRepl()
        // Same-JVM: the rule lives in the live compile-time space, which init now
        // registers into the runtime registry — so `match &self` finds it (previously
        // the runtime space was a fresh empty one and this returned nothing).
        repl.eval("""
            (= (foo) 0)
        """.trimIndent()).let { assertTrue(it.isSuccess) }
        repl.eval("""!(match &self (= (foo) _x) _x)""".replace('_', '$')).let {
            it.messages.forEach(::println)
            assertTrue(it.isSuccess)
            assertEquals("[0]", it.result.toString())
        }
    }

    @Test
    fun `jit-eval of match self routes to the live space`() {
        val repl = createRepl()
        repl.eval("""
            (= (foo) 0)
        """.trimIndent()).let { assertTrue(it.isSuccess) }
        // The eval'd `match &self` bakes the CALLER's space name (via the Generator
        // override), so it routes to the running program's live space — not an empty
        // space named after the throwaway synthetic eval class.
        repl.eval("""!(eval '(match &self (= (foo) _x) _x))""".replace('_', '$')).let {
            it.messages.forEach(::println)
            assertTrue(it.isSuccess)
            assertEquals("[0]", it.result.toString())
        }
    }

    private fun createRepl(): Repl = ReplImpl()
}