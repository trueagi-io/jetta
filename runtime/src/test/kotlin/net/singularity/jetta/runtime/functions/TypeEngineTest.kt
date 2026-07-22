package net.singularity.jetta.runtime.functions

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Special
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for the [TypeEngine] — [TypeEngine.inferType] / [TypeEngine.unify] over
 * hand-built `Atom`s, with no codegen or space serialization. Mirrors the `d1_gadt`/`d3` cases
 * the `get-type` builtin composes on top of.
 */
class TypeEngineTest {

    // --- builders ------------------------------------------------------------------------
    private fun sym(name: String) = Symbol(name)
    private fun v(name: String) = Variable(name)
    private fun num(n: Int) = Grounded(n)
    private fun str(s: String) = Grounded(s)
    private fun expr(vararg a: Atom) = Expression(atoms = a.toList())
    private fun arrow(vararg a: Atom) = Expression(atoms = listOf(Special("->")) + a.toList())

    /** `(: subject type)` fact. */
    private fun typeFact(subject: Atom, type: Atom) = Expression(atoms = listOf(Special(":"), subject, type))

    private val NUMBER = sym("Number")
    private val STRING = sym("String")
    private val BOOL = sym("Bool")

    // --- leaves --------------------------------------------------------------------------

    @Test
    fun `literal types`() {
        assertEquals(NUMBER, TypeEngine.inferType(num(5), emptyList()))
        assertEquals(STRING, TypeEngine.inferType(str("x"), emptyList()))
        assertEquals(BOOL, TypeEngine.inferType(Grounded(true), emptyList()))
    }

    @Test
    fun `bare built-in operator has an arrow type`() {
        assertEquals(arrow(NUMBER, NUMBER, NUMBER), TypeEngine.inferType(Special("+"), emptyList()))
        assertEquals(arrow(NUMBER, NUMBER, BOOL), TypeEngine.inferType(Special("<"), emptyList()))
    }

    // --- application ---------------------------------------------------------------------

    @Test
    fun `arithmetic application returns Number`() {
        assertEquals(NUMBER, TypeEngine.inferType(expr(Special("+"), num(5), num(7)), emptyList()))
    }

    @Test
    fun `ill-typed operand makes the expression ill-typed`() {
        assertNull(TypeEngine.inferType(expr(Special("+"), num(5), str("4")), emptyList()))
    }

    @Test
    fun `arity mismatch is ill-typed`() {
        assertNull(TypeEngine.inferType(expr(Special("+"), Special("-")), emptyList()))
    }

    @Test
    fun `declared type of a symbol`() {
        val atoms = listOf(typeFact(sym("Either"), sym("Type")))
        assertEquals(sym("Type"), TypeEngine.inferType(sym("Either"), atoms))
    }

    @Test
    fun `undefined-wildcard parameter accepts any argument`() {
        val atoms = listOf(typeFact(sym("Left"), arrow(sym("%Undefined%"), sym("Either"))))
        assertEquals(sym("Either"), TypeEngine.inferType(expr(sym("Left"), num(5)), atoms))
    }

    @Test
    fun `nested arrow application`() {
        val atoms = listOf(
            typeFact(sym("Right"), arrow(sym("%Undefined%"), sym("Either"))),
            typeFact(sym("isLeft"), arrow(sym("Either"), BOOL)),
        )
        assertEquals(BOOL, TypeEngine.inferType(expr(sym("isLeft"), expr(sym("Right"), num(5))), atoms))
        // Argument type Number does not unify with Either → ill-typed.
        assertNull(TypeEngine.inferType(expr(sym("isLeft"), num(5)), atoms))
    }

    @Test
    fun `parametric type variable binds and substitutes into the result`() {
        val atoms = listOf(
            typeFact(sym("EitherP"), arrow(v("t"), sym("Type"))),
            typeFact(sym("LeftP"), arrow(v("t"), expr(sym("EitherP"), v("t")))),
        )
        assertEquals(
            expr(sym("EitherP"), NUMBER),
            TypeEngine.inferType(expr(sym("LeftP"), num(5)), atoms),
        )
    }

    @Test
    fun `recursive parametric type with fresh-var non-collision`() {
        val atoms = listOf(
            typeFact(sym("List"), arrow(v("a"), sym("Type"))),
            typeFact(sym("Nil"), expr(sym("List"), v("a"))),
            typeFact(sym("Cons"), arrow(v("a"), expr(sym("List"), v("a")), expr(sym("List"), v("a")))),
        )
        // Two independent `Nil` uses share the surface name `$a`; fresh instantiation keeps them
        // from colliding, so both nested Cons infer `(List Number)`.
        val consChain = expr(sym("Cons"), num(5), expr(sym("Cons"), num(6), sym("Nil")))
        assertEquals(expr(sym("List"), NUMBER), TypeEngine.inferType(consChain, atoms))
        // A heterogeneous list is ill-typed — the inner Cons infers `(List String)`, which the
        // outer `unify(Number, String)` rejects.
        val mixed = expr(sym("Cons"), num(5), expr(sym("Cons"), str("6"), sym("Nil")))
        assertNull(TypeEngine.inferType(mixed, atoms))
    }

    @Test
    fun `dependent Vec type`() {
        val atoms = listOf(
            typeFact(sym("Nat"), sym("Type")),
            typeFact(sym("Z"), sym("Nat")),
            typeFact(sym("S"), arrow(sym("Nat"), sym("Nat"))),
            typeFact(sym("Vec"), arrow(v("t"), sym("Nat"), sym("Type"))),
            typeFact(sym("Cons"), arrow(v("t"), expr(sym("Vec"), v("t"), v("x")), expr(sym("Vec"), v("t"), expr(sym("S"), v("x"))))),
            typeFact(sym("Nil"), expr(sym("Vec"), v("t"), sym("Z"))),
            typeFact(sym("drop"), arrow(expr(sym("Vec"), v("t"), expr(sym("S"), v("x"))), expr(sym("Vec"), v("t"), v("x")))),
        )
        val vec2 = expr(sym("Cons"), num(0), expr(sym("Cons"), num(1), sym("Nil")))
        assertEquals(
            expr(sym("Vec"), NUMBER, expr(sym("S"), expr(sym("S"), sym("Z")))),
            TypeEngine.inferType(vec2, atoms),
        )
        // drop peels one S
        assertEquals(
            expr(sym("Vec"), NUMBER, sym("Z")),
            TypeEngine.inferType(expr(sym("drop"), expr(sym("Cons"), num(1), sym("Nil"))), atoms),
        )
        // dropping from an empty Vec: unify (S $x) with Z fails → ill-typed
        assertNull(TypeEngine.inferType(expr(sym("drop"), sym("Nil")), atoms))
    }

    @Test
    fun `grounded op in the result type is reduced`() {
        val atoms = listOf(
            typeFact(sym("VecN"), arrow(v("t"), NUMBER, sym("Type"))),
            typeFact(sym("ConsN"), arrow(v("t"), expr(sym("VecN"), v("t"), v("x")), expr(sym("VecN"), v("t"), expr(Special("+"), v("x"), num(1))))),
            typeFact(sym("NilN"), expr(sym("VecN"), v("t"), num(0))),
        )
        val chain = expr(sym("ConsN"), str("1"), expr(sym("ConsN"), str("2"), sym("NilN")))
        assertEquals(expr(sym("VecN"), STRING, num(2)), TypeEngine.inferType(chain, atoms))
    }

    // --- unify ---------------------------------------------------------------------------

    @Test
    fun `unify basics`() {
        assertTrue(TypeEngine.unify(NUMBER, NUMBER, HashMap()))
        assertFalse(TypeEngine.unify(NUMBER, STRING, HashMap()))
        // A variable binds; the binding is readable back via resolve.
        val s = HashMap<String, Atom>()
        assertTrue(TypeEngine.unify(expr(sym("List"), v("t")), expr(sym("List"), NUMBER), s))
        assertEquals(NUMBER, TypeEngine.resolve(v("t"), s))
    }
}
