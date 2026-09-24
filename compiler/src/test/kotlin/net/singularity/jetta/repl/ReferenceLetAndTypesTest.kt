package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * A pattern-`let` is the reference's `(unify VAL PAT BODY Empty)` over an evaluated VAL, a `let`
 * over a `match` runs once per result, `get-type` is non-deterministic, and ordered top-level
 * visibility hides only STATIC facts written below the running form. Each case is a corpus file
 * or a measured answer of hyperon's `metta-repl`.
 */
class ReferenceLetAndTypesTest {
    private fun repl() = ReplImpl()
    private fun String.d() = replace('_', '$')

    private fun ReplImpl.ok(code: String) =
        eval(code.d()).let { assertTrue(it.isSuccess, it.messages.joinToString("\n")) }

    @Test
    fun `a pattern let binds the variables of the value side too`() {
        // corpus letlet
        repl().ok("""
            (= (f) (let* (((_f1 _c1 3) (1 2 _d1))) (_f1 _c1 _d1)))
            !(assertEqual (f) (1 2 3))
        """.trimIndent())
    }

    @Test
    fun `the check can fail - a wrong let result is reported`() {
        val r = repl()
        r.ok("(= (f) (let* (((_f1 _c1 3) (1 2 _d1))) (_f1 _c1 _d1)))")
        assertFails { r.eval("!(assertEqual (f) (1 2 4))") }
    }

    @Test
    fun `a ground pattern let tests the value and substitutes what it bound`() {
        // corpus types, hyperon answers (a b)
        repl().ok("""
            (= (mid _x) (let (a b) _x _x))
            !(assertEqual (mid (_a b)) (a b))
            !(assertEqual (collapse (mid (c d))) ())
        """.trimIndent())
    }

    @Test
    fun `a let over a match runs once per result`() {
        repl().ok("""
            (p a 1)
            (p b 2)
            (= (f) (let _r (match &self (p _x _y) _y) (+ _r 10)))
            !(assertEqual (collapse (f)) (11 12))
        """.trimIndent())
    }

    @Test
    fun `get-type answers every declared type, and types a tuple of typed elements`() {
        // corpus recursive_types; the tuple rule measured against metta-repl
        repl().ok("""
            (: blacksmith (-> Metal Sword))
            (: blacksmith (-> Metal Paperclip))
            (: iron Metal)
            !(assertEqual (collapse (get-type blacksmith)) ((-> Metal Sword) (-> Metal Paperclip)))
            !(assertEqual (collapse (get-type (blacksmith iron))) (Sword Paperclip))
            !(assertEqual (collapse (get-type (iron iron))) ((Metal Metal)))
            !(assertEqual (collapse (get-type (iron foo))) (%Undefined%))
        """.trimIndent())
    }
}
