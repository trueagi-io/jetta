package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The trie is a candidate filter: it may over-approximate (return a structurally-compatible
 * atom that ultimately fails the exact check) but must NEVER drop a real match — a false
 * negative would make `match` silently miss facts. These tests pin the no-false-negative
 * invariant across both wildcard directions (stored variable, query variable).
 */
class DiscriminationTrieTest {

    private fun eval(pred: String, arg: String) =
        Expression(Symbol("Evaluation"), Expression(Symbol(pred), Symbol(arg)))

    @Test
    fun `ground query keeps compatible facts and drops the rest`() {
        val t = DiscriminationTrie()
        t.insert(eval("mortal", "p5"), 0)
        t.insert(eval("noise", "n1"), 1)
        t.insert(eval("mortal", "p6"), 2)
        // Only the structurally-identical fact survives: different inner head (noise) and
        // different argument (p6) diverge at their trie branch.
        assertEquals(listOf(0), t.lookup(eval("mortal", "p5")))
    }

    @Test
    fun `stored-variable head matches a ground query at that position`() {
        val t = DiscriminationTrie()
        // A rule head: (Implication (Evaluation (human $x)) (Evaluation (mortal $x)))
        val rule = Expression(
            Symbol("Implication"),
            Expression(Symbol("Evaluation"), Expression(Symbol("human"), Variable("x"))),
            Expression(Symbol("Evaluation"), Expression(Symbol("mortal"), Variable("x"))),
        )
        t.insert(rule, 7)
        // Query: (Implication $a (Evaluation (mortal p5))) — $a is a query var (skips the 2nd
        // element), and stored $x wildcards the p5 position. Must be found.
        val query = Expression(
            Symbol("Implication"),
            Variable("a"),
            Expression(Symbol("Evaluation"), Expression(Symbol("mortal"), Symbol("p5"))),
        )
        assertTrue(7 in t.lookup(query))
    }

    @Test
    fun `query variable matches any stored subterm`() {
        val t = DiscriminationTrie()
        t.insert(eval("mortal", "p5"), 0)
        t.insert(eval("philosopher", "p5"), 1)
        t.insert(eval("mortal", "p6"), 2)
        // (Evaluation ($P p5)) — $P matches both predicates but p5 pins the argument.
        val q = Expression(Symbol("Evaluation"), Expression(Variable("P"), Symbol("p5")))
        assertEquals(listOf(0, 1), t.lookup(q))
        // (Evaluation $whole) — matches every Evaluation fact.
        val qAll = Expression(Symbol("Evaluation"), Variable("whole"))
        assertEquals(listOf(0, 1, 2), t.lookup(qAll))
    }

    @Test
    fun `arity and head mismatches are excluded, empty store is empty`() {
        val t = DiscriminationTrie()
        assertTrue(t.lookup(eval("mortal", "p5")).isEmpty())
        t.insert(eval("mortal", "p5"), 0)
        // Wrong outer head.
        assertTrue(t.lookup(Expression(Symbol("Nope"), Expression(Symbol("mortal"), Symbol("p5")))).isEmpty())
        // Wrong arity at the inner expression.
        assertTrue(
            t.lookup(Expression(Symbol("Evaluation"), Expression(Symbol("mortal"), Symbol("p5"), Symbol("extra"))))
                .isEmpty()
        )
    }
}
