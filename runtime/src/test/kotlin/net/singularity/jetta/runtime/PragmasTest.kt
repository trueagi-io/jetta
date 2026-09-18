package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `pragma!` and the one setting it acts on.
 *
 * The expectations are the reference's own, read off `PragmaOp::execute`
 * (`lib/src/metta/runner/stdlib/core.rs`) and its `test_pragma_max_stack_depth`: the call answers
 * the unit atom, `max-stack-depth` must be an unsigned integer or the call answers
 * `(Error (pragma! max-stack-depth -12) UnsignedIntegerIsExpected)`, and `0` means no bound.
 */
class PragmasTest {

    private val unit = Expression(emptyList())

    @BeforeTest
    fun clean() = Pragmas.reset()

    @AfterTest
    fun cleanAfter() = Pragmas.reset()

    private fun pragma(key: String, value: Atom): Atom = Pragmas.`pragma!`(Symbol(key), value)

    @Test
    fun `a pragma is recorded and answers the unit atom`() {
        assertEquals(unit, pragma("type-check", Symbol("auto")))
        assertEquals(Symbol("auto"), Pragmas.setting("type-check"))
    }

    @Test
    fun `max-stack-depth takes an unsigned integer`() {
        assertEquals(unit, pragma(Pragmas.MAX_STACK_DEPTH, Grounded(200)))
        assertEquals(200, Pragmas.maxStackDepth())
    }

    /** `0` is the reference's "no bound", and it must be able to turn an earlier bound OFF. */
    @Test
    fun `zero means no bound`() {
        pragma(Pragmas.MAX_STACK_DEPTH, Grounded(21))
        pragma(Pragmas.MAX_STACK_DEPTH, Grounded(0))
        assertEquals(0, Pragmas.maxStackDepth())
    }

    @Test
    fun `a negative depth answers the reference's error term and sets nothing`() {
        val answer = pragma(Pragmas.MAX_STACK_DEPTH, Grounded(-12))
        assertEquals(
            Expression(
                Symbol("Error"),
                Expression(Symbol("pragma!"), Symbol(Pragmas.MAX_STACK_DEPTH), Grounded(-12)),
                Symbol("UnsignedIntegerIsExpected"),
            ),
            answer,
        )
        assertTrue(Errors.isError(answer))
        assertEquals(0, Pragmas.maxStackDepth())
    }

    @Test
    fun `a non-numeric depth is rejected the same way`() {
        assertTrue(Errors.isError(pragma(Pragmas.MAX_STACK_DEPTH, Symbol("lots"))))
        assertEquals(0, Pragmas.maxStackDepth())
    }

    @Test
    fun `reset clears the settings, as init does per program`() {
        pragma(Pragmas.MAX_STACK_DEPTH, Grounded(21))
        Pragmas.reset()
        assertEquals(0, Pragmas.maxStackDepth())
        assertNull(Pragmas.setting(Pragmas.MAX_STACK_DEPTH))
    }

    /** Unbounded: the error is handed back AS ITSELF, trace and all — we promised no bound. */
    @Test
    fun `an unbounded stack overflow stays a StackOverflowError`() {
        val error = StackOverflowError()
        assertSame(error, Errors.stackOverflow(error, "(down 5)"))
    }

    /** Bounded: the reference's term, which then ends the program like any top-level error. */
    @Test
    fun `a bounded stack overflow becomes the reference's error term`() {
        pragma(Pragmas.MAX_STACK_DEPTH, Grounded(100))
        val thrown = Errors.stackOverflow(StackOverflowError(), "(down 5)")
        assertTrue(thrown is MettaError, "expected MettaError, got $thrown")
        assertEquals(
            Expression(Symbol("Error"), Grounded("(down 5)"), Symbol("StackOverflow")),
            thrown.error,
        )
    }
}
