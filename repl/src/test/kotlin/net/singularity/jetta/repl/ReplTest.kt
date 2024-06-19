package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun createRepl(): Repl = ReplImpl()
}