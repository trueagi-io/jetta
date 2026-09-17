package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `(Error …)` predicate generated `__main` consults after every top-level `!`-run, and the
 * throw that ends the program when a run answered one — hyperon's `atom_is_error` plus the
 * `MettaRunnerMode::TERMINATE` it triggers.
 */
class ErrorsTest {

    private fun sym(name: String) = Symbol(name)
    private fun expr(vararg atoms: Atom): Expression = Expression(atoms.toList())

    private fun error(): Expression =
        expr(sym("Error"), expr(sym("+"), Grounded(5), Grounded("S")), sym("BadArgType"))

    @Test
    fun `an expression headed by Error is an error term`() {
        assertTrue(Errors.isError(error()))
    }

    @Test
    fun `ordinary values are not error terms`() {
        assertFalse(Errors.isError(sym("Error")))          // the bare symbol is not an application
        assertFalse(Errors.isError(expr()))                // the empty tuple `()`
        assertFalse(Errors.isError(expr(sym("foo"), sym("Error"))))
        assertFalse(Errors.isError(Grounded(42)))
        assertFalse(Errors.isError(null))
    }

    /**
     * The predicate is SHALLOW, as hyperon's is: a term that CONTAINS an error is an ordinary
     * value — `if-error` / `return-on-error` exist precisely to be handed one — and only an error
     * in the run's own result position ends the program.
     */
    @Test
    fun `an error nested inside a term is not the run's error`() {
        val nested = expr(sym("foo"), error())
        assertFalse(Errors.isError(nested))
        assertNull(Errors.firstError(nested))
        Errors.checkRunResult(nested)
    }

    @Test
    fun `a BoundAtom is unwrapped before the head is read`() {
        assertTrue(Errors.isError(BoundAtom(error(), emptyMap())))
        assertEquals(error(), Errors.firstError(BoundAtom(error(), emptyMap())))
    }

    /** A multivalued run answers a bag, and ANY error in it terminates (`result.iter().any`). */
    @Test
    fun `an error anywhere in a result bag is found`() {
        assertEquals(error(), Errors.firstError(listOf(sym("A"), error(), sym("B"))))
        assertNull(Errors.firstError(listOf(sym("A"), sym("B"))))
        assertNull(Errors.firstError(emptyList<Atom>()))
    }

    @Test
    fun `checkRunResult throws MettaError carrying the term`() {
        val thrown = assertFailsWith<MettaError> { Errors.checkRunResult(error()) }
        assertEquals(error(), thrown.error)
        // The message is the term itself, which is what the reference prints as that run's result.
        assertEquals("${error()}", thrown.message)
    }

    @Test
    fun `checkRunResult passes an ordinary result through`() {
        Errors.checkRunResult(sym("A"))
        Errors.checkRunResult(listOf(sym("A"), sym("B")))
        Errors.checkRunResult(42)
        Errors.checkRunResult(null)
    }
}
