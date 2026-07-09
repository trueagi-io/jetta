package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import kotlin.test.Test
import kotlin.test.assertEquals

class ConvertTest {
    private fun sym(name: String) = Symbol(name)

    // --- superpose: tuple -> nondeterministic (List) results ---------------------

    @Test
    fun `superpose turns a tuple into its elements`() {
        val result = Convert.superpose(Expression(sym("a"), sym("b"), sym("c")))
        assertEquals(listOf(sym("a"), sym("b"), sym("c")), result)
    }

    @Test
    fun `superpose of empty tuple yields no results`() {
        assertEquals(emptyList(), Convert.superpose(Expression()))
    }

    @Test
    fun `superpose of a non-expression yields a single result`() {
        assertEquals(listOf(sym("x")), Convert.superpose(sym("x")))
    }

    // --- collapse: nondeterministic (List) results -> tuple ----------------------

    @Test
    fun `collapse gathers a bag of results into a tuple`() {
        val result = Convert.collapse(listOf(sym("green"), sym("yellow"), sym("red")))
        assertEquals(Expression(sym("green"), sym("yellow"), sym("red")), result)
    }

    @Test
    fun `collapse of empty results is the empty tuple`() {
        assertEquals(Expression(), Convert.collapse(emptyList<Any?>()))
    }

    @Test
    fun `collapse of a single-valued result wraps it in a one-element tuple`() {
        // e.g. `(collapse (shape))` where `(shape)` does not reduce -> `((shape))`
        val shape = Expression(sym("shape"))
        assertEquals(Expression(shape), Convert.collapse(shape))
    }

    @Test
    fun `collapse unwraps BoundAtom branches`() {
        val a = sym("a")
        assertEquals(Expression(a), Convert.collapse(listOf(BoundAtom(a, emptyMap()))))
    }

    // --- superpose / collapse round-trip ----------------------------------------

    @Test
    fun `superpose is the inverse of collapse`() {
        val bag = listOf(sym("green"), sym("yellow"), sym("red"))
        assertEquals(bag, Convert.superpose(Convert.collapse(bag)))
    }
}
