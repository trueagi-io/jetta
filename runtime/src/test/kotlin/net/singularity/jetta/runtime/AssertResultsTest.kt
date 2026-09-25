package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `_assert-results-are-*` family — the grounded comparison hyperon's `stdlib.metta` builds
 * every `assertEqual*` on, and the reason `assertAlphaEqualToResult` can run here at all.
 *
 * The bags arrive as the library produces them: `(metta (collapse $actual) %Undefined% $space)`
 * answers one COLLAPSED TUPLE inside the usual multivalued result `List`, so both that shape and
 * a bare tuple must read as the same bag.
 */
class AssertResultsTest {

    private fun sym(name: String) = Symbol(name)
    private fun v(name: String) = Variable(name)

    /** One collapsed tuple, as `collapse` builds it. */
    private fun tuple(vararg results: Atom): Atom = Expression(results.toList())

    /** The tuple wrapped in a result bag, as it reaches the call through `metta`. */
    private fun bagged(vararg results: Atom): Any = listOf(tuple(*results))

    @Test
    fun `equal bags answer the unit atom`() {
        val unit = Assertions.`_assert-results-are-equal`(
            bagged(sym("A"), sym("B")), tuple(sym("B"), sym("A")), sym("the-assert"),
        )
        assertEquals(Expression(emptyList()), unit)
    }

    /** A bare tuple and a tuple inside a one-element result bag denote the same bag. */
    @Test
    fun `the collapsed tuple is read through the result bag that wraps it`() {
        Assertions.`_assert-results-are-equal`(bagged(sym("A")), tuple(sym("A")), sym("a"))
        Assertions.`_assert-results-are-equal`(tuple(sym("A")), bagged(sym("A")), sym("a"))
    }

    @Test
    fun `a differing bag fails and the message carries the assert term`() {
        val e = assertFailsWith<AssertionError> {
            Assertions.`_assert-results-are-equal`(
                bagged(Grounded(3)), tuple(sym("WILDLY"), sym("WRONG")),
                Expression(sym("assertEqualToResult"), Grounded(3)),
            )
        }
        val message = e.message ?: ""
        assertTrue(message.contains("(assertEqualToResult 3)"), "assert term missing from:\n$message")
        assertTrue(message.contains("Expected") && message.contains("Got"), message)
    }

    /** Multiplicity counts, not just membership — the reference counts per bucket too. */
    @Test
    fun `multiplicity is compared`() {
        assertFailsWith<AssertionError> {
            Assertions.`_assert-results-are-equal`(
                bagged(sym("A"), sym("A")), tuple(sym("A"), sym("B")), sym("a"),
            )
        }
    }

    /** This is d5_auto_types' assert: the expected term carries a free variable. */
    @Test
    fun `the alpha variant compares up to a renaming of variables`() {
        Assertions.`_assert-results-are-alpha-equal`(
            bagged(Expression(sym("BadArgType"), Grounded(1), Expression(sym("Human"), v("t")))),
            tuple(Expression(sym("BadArgType"), Grounded(1), Expression(sym("Human"), v("x")))),
            sym("a"),
        )
        // …and the plain comparison, which keys variables by NAME, does not accept it.
        assertFailsWith<AssertionError> {
            Assertions.`_assert-results-are-equal`(
                bagged(Expression(sym("Human"), v("t"))),
                tuple(Expression(sym("Human"), v("x"))),
                sym("a"),
            )
        }
    }

    /** The renaming has to be a bijection: two distinct variables cannot both map to one. */
    @Test
    fun `the alpha variant requires a bijection`() {
        assertFailsWith<AssertionError> {
            Assertions.`_assert-results-are-alpha-equal`(
                bagged(Expression(sym("f"), v("a"), v("b"))),
                tuple(Expression(sym("f"), v("c"), v("c"))),
                sym("a"),
            )
        }
    }

    /**
     * Each element joins the first bucket it is equivalent to, so a bag of alpha-equivalent
     * elements is compared by multiplicity like any other — `[(f $a) (f $a)]` is not
     * `[(f $a) (g $a)]` even though every element of the second is alpha-equivalent to something.
     */
    @Test
    fun `alpha buckets still count multiplicity`() {
        Assertions.`_assert-results-are-alpha-equal`(
            bagged(Expression(sym("f"), v("a")), Expression(sym("g"), v("a"))),
            tuple(Expression(sym("g"), v("y")), Expression(sym("f"), v("x"))),
            sym("a"),
        )
        assertFailsWith<AssertionError> {
            Assertions.`_assert-results-are-alpha-equal`(
                bagged(Expression(sym("f"), v("a")), Expression(sym("f"), v("a"))),
                tuple(Expression(sym("f"), v("x")), Expression(sym("g"), v("y"))),
                sym("a"),
            )
        }
    }

    /** The `-msg` pair reports the caller's message in place of the generated diff. */
    @Test
    fun `the msg variant reports the caller's message`() {
        val e = assertFailsWith<AssertionError> {
            Assertions.`_assert-results-are-equal-msg`(
                bagged(sym("A")), tuple(sym("B")), sym("a"), Symbol("my-own-message"),
            )
        }
        val message = e.message ?: ""
        assertTrue(message.contains("my-own-message"), message)
        assertTrue(!message.contains("Expected"), "the generated report should be replaced:\n$message")
    }
}
