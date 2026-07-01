package net.singularity.jetta.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comparisons where an operand is an `Atom` at runtime (a destructured `Grounded`
 * number) but the other side is a primitive: `(== $a 0)`, `(> $a $b)`. Before the fix
 * these routed to `Object.equals` / raw int ops on a reference and hit a VerifyError.
 * Now the Atom operand is unwrapped to the primitive and compared by value — matching
 * hyperon's grounded-op semantics. Pure symbol==symbol still uses reference equality.
 */
class ComparisonOverAtomTest {
    private fun repl() = ReplImpl()
    private fun String.d() = replace('_', '$')

    @Test
    fun `equality of a destructured value against an int literal`() {
        val r = repl()
        r.eval("(= (pick (Pair _a _b)) (if (== _a 0) _b _a))".d())
        r.eval("""!(pick (Pair 0 7))""").let { assertTrue(it.isSuccess); assertEquals("[7]", it.result.toString()) }
        r.eval("""!(pick (Pair 3 7))""").let { assertTrue(it.isSuccess); assertEquals("[3]", it.result.toString()) }
    }

    @Test
    fun `ordering of two destructured values`() {
        val r = repl()
        r.eval("(= (mx (Pair _a _b)) (if (> _a _b) _a _b))".d())
        r.eval("""!(mx (Pair 3 8))""").let { assertTrue(it.isSuccess); assertEquals("[8]", it.result.toString()) }
        r.eval("""!(mx (Pair 9 2))""").let { assertTrue(it.isSuccess); assertEquals("[9]", it.result.toString()) }
    }

    @Test
    fun `symbol equality still uses reference comparison`() {
        repl().eval("""!(if (== a a) 1 0)""").let { assertTrue(it.isSuccess); assertEquals(1, it.result) }
    }
}
