package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * `case` is the reference's: patterns are UNIFIED, the first clause that unifies wins, a result
 * no clause takes yields nothing, and an `Empty` clause fires when the value has no result at
 * all. And a `match` template that BRANCHES (`if` / `unify` / `case`) is evaluated once per
 * match, with the pattern's variables bound to that match's values.
 *
 * Mirrors corpus `caseempty`, `ifcasenondet`, `multicall` and the `get_score` shape of
 * `greedy_chess`.
 */
class CaseAndBranchingTemplateTest {
    private fun repl() = ReplImpl()
    private fun String.d() = replace('_', '$')

    private fun ReplImpl.ok(code: String) =
        eval(code.d()).let { assertTrue(it.isSuccess, it.messages.joinToString("\n")) }

    @Test
    fun `an Empty clause fires when the value has no result`() {
        val r = repl()
        r.ok("(= (wu) (case (empty) ((1 2) (Empty 42))))")
        r.ok("!(assertEqual (wu) 42)")
    }

    @Test
    fun `an Empty clause does not fire when the value has one`() {
        val r = repl()
        r.ok("(= (f) 42)\n(= (wu2) (case (f) ((42 ok) (Empty nok))))")
        r.ok("!(assertEqual (wu2) ok)")
    }

    @Test
    fun `case runs per result of a superpose, and a Bool read back as data unifies with a literal`() {
        val r = repl()
        r.ok("(= (cn _y) (case (superpose _y) ((True a) (False b))))")
        r.ok("!(assertEqual (collapse (cn (True False True))) (a b a))")
    }

    @Test
    fun `the check can fail - a wrong case answer is reported`() {
        val r = repl()
        r.ok("(= (cn _y) (case (superpose _y) ((True a) (False b))))")
        assertFails { r.eval("!(assertEqual (collapse (cn (True False True))) (a a a))") }
    }

    @Test
    fun `a value no clause takes yields nothing`() {
        val r = repl()
        r.ok("(= (pick _x) (case _x ((1 one) (2 two))))")
        r.ok("!(assertEqual (collapse (pick 3)) ())")
    }

    @Test
    fun `collapse of a scalar Int call boxes the value`() {
        val r = repl()
        r.ok("(: n (-> Int))\n(= (n) 7)")
        r.ok("!(assertEqual (collapse (n)) (7))")
    }

    @Test
    fun `a match template with if, unify or case sees the match's bindings`() {
        val r = repl()
        // One program, as a file would be: the facts and the queries over them together.
        r.ok("""
            (p a 1)
            (p b 2)
            (= (f) (match &self (p _x _y) (if (== _y 1) one other)))
            (= (g) (match &self (p _x _y) (unify _y 1 one other)))
            (= (h) (match &self (p _x _y) (case _y ((1 one) (2 two)))))
            !(assertEqual (collapse (f)) (one other))
            !(assertEqual (collapse (g)) (one other))
            !(assertEqual (collapse (h)) (one two))
        """.trimIndent())
    }
}
