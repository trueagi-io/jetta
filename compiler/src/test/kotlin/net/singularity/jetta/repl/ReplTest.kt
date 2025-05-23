package net.singularity.jetta.repl

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
            (foo 1 2)
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
            (bar 2)
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
            assertEquals(5, it.result)
        }
    }

    @Test
    fun evalExpression() {
        val repl = createRepl()
        repl.eval("""
            (+ 1 2)
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
            assertEquals(3, it.result)
        }
    }

    @Test
    fun expressionBeforeDefinition() {
        val repl = createRepl()
        repl.eval("""
            (+ 1 2)
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
            (bar 2)
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
        }
        repl.eval("""
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y 2))
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
        }
        repl.eval("""
            (bar 2)
        """.trimIndent()).let {
            assertTrue(it.isSuccess)
            assertEquals(5, it.result)
        }
        repl.eval("""
            (foo 1 2)
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
            (foo 1 2)
        """.trimIndent()).let {
            assertTrue(it.isSuccess)
            assertEquals(4, it.result)
        }
        repl2.eval("""
            (foo 2)
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
            (foo 10 20 (\ (_x _y) (+ _x _y)))
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
            (foo 10 20 bar)
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
           
            (bar)
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
           
            (+ 1 (bar))
        """.trimIndent().replace('_', '$')).let {
            assertTrue(it.isSuccess)
            assertTrue(it.messages.isEmpty())
            assertEquals(listOf(3, 4, 5), it.result)
        }
    }

    @Test
    fun `recover from a failure`() {
        val repl = createRepl()
        repl.eval("""(+ 1 (foo 2))""").let {
            assertFalse(it.isSuccess)
        }
        repl.eval(
            """
            (: log-int (-> Int Int))
            (= (log-int _x)
                (if (== _x 1) 0 (+ 1 (log-int (- _x 1))))
            )
            (log-int 8)
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
            (foo)
        """.trimIndent()).let {
            assertTrue(it.isSuccess)
        }
    }

    private fun createRepl(): Repl = ReplImpl()
}