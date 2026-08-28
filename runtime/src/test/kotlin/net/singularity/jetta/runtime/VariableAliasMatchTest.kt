package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.SpaceImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A pattern variable meeting a STORE variable. Unification binds both ways, so a template written
 * in terms of the store's variable has to render as the pattern's.
 *
 * `(= ((lambda $var $body) $arg) (let $var $arg $body))` queried by `((lambda $x (+ $x 1)) 2)` used
 * to render `(let $var 2 (+ $x 1))`: `$body` and `$arg` were substituted (they meet non-variables)
 * while `$var` — the one position where both sides are variables — stayed as written, leaving a
 * `let` binding a variable nothing mentions.
 *
 * The binding is WEAK: the same store variable may meet a pattern variable in one position and a
 * VALUE in another, and the value is the answer. Recording it eagerly made the concrete arm's
 * consistency check reject such a match outright.
 */
class VariableAliasMatchTest {

    private fun space(vararg facts: Expression) =
        SpaceImpl().apply { enablePerCallBindings = false; facts.forEach { add(it) } }

    @Test
    fun `a template variable that met a pattern variable renders as the pattern's`() {
        // store: (rule (f $v) (wrap $v))   query: (rule (f $x) $out)
        val store = Expression(
            Symbol("rule"),
            Expression(Symbol("f"), Variable("v")),
            Expression(Symbol("wrap"), Variable("v")),
        )
        val results = space(store).match(
            Expression(Symbol("rule"), Expression(Symbol("f"), Variable("x")), Variable("out")),
            Variable("out"),
        )

        assertEquals(1, results.size)
        val wrap = results[0] as Expression
        assertEquals(Symbol("wrap"), wrap.atoms[0])
        assertEquals("x", (wrap.atoms[1] as Variable).name, "the store's variable renders as the pattern's")
    }

    @Test
    fun `a concrete binding wins over the variable alias`() {
        // store: (implies (Frog $x) (Green $x))  query: (implies ($P $x) (Green Sam))
        // `$x` meets the pattern's `$x` on the left and `Sam` on the right — `Sam` is the answer,
        // and the match must not be rejected for the disagreement.
        val store = Expression(
            Symbol("implies"),
            Expression(Symbol("Frog"), Variable("x")),
            Expression(Symbol("Green"), Variable("x")),
        )
        val results = space(store).match(
            Expression(
                Symbol("implies"),
                Expression(Variable("P"), Variable("x")),
                Expression(Symbol("Green"), Symbol("Sam")),
            ),
            Expression(Variable("x"), Symbol("might"), Symbol("be"), Variable("P")),
        )

        assertEquals(1, results.size, "the match must not be rejected")
        assertEquals(
            listOf(Symbol("Sam"), Symbol("might"), Symbol("be"), Symbol("Frog")),
            (results[0] as Expression).atoms,
        )
    }

    @Test
    fun `one store variable meeting two different pattern variables still matches`() {
        // store: (= (f $x) $x)  query: (= (f $a) $r) — the ordinary reduction query, where the
        // store's `$x` meets `$a` on the left and the result variable on the right.
        val store = Expression(
            Symbol("="),
            Expression(Symbol("f"), Variable("x")),
            Variable("x"),
        )
        val results = space(store).match(
            Expression(Symbol("="), Expression(Symbol("f"), Variable("a")), Variable("r")),
            Variable("r"),
        )

        assertTrue(results.isNotEmpty(), "a store variable in two positions must not fail the match")
    }
}
