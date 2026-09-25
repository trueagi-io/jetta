package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import net.singularity.jetta.runtime.MettaError
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Codegen side of `(Error …)`-termination: generated `__main` hands each top-level run's result to
 * `Errors.checkRunResult`, which throws out of `__main` when the run answered an error — the
 * compiled equivalent of the reference switching its runner to `MettaRunnerMode::TERMINATE`.
 *
 * The check is emitted only for a step whose value is a JVM REFERENCE. A primitive or `void`
 * result can not be an error term, and `DUP`ing one would be either pointless or (for a
 * category-2 primitive) wrong — so a program of primitive-valued runs must still verify, which
 * is what the second test is for.
 */
class TopLevelErrorTest : GeneratorTestBase() {

    private fun compileAndRun(name: String, code: String) {
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach(::println)
                assertTrue(messageCollector.list().isEmpty())
                val classes = result.toMap().toClasses()
                JettaProgram.init(name)
                classes[name]!!.getMethod("__main").invoke(null)
            }
    }

    /** The error comes out of `__main`, carrying the term, and the trailing `Int` run verifies. */
    @Test
    fun `a top-level error throws MettaError out of main`() {
        val thrown = try {
            compileAndRun(
                "TopLevelError",
                """
                !(println! ran)
                !(+ 5 "S")
                !(+ 1 2)
                """.trimIndent()
            )
            null
        } catch (e: InvocationTargetException) {
            e.targetException
        }
        assertTrue(thrown is MettaError, "expected MettaError, got $thrown")
        assertEquals("(Error (+ 5 S) (BadArgType 2 Number String))", thrown.message)
    }

    /**
     * `max-stack-depth` asked for → a runaway recursion answers the reference's
     * `(Error <run> StackOverflow)` from the run's own handler, and that ends the program.
     * Runs on the test thread's ordinary stack, so the bound is reached in milliseconds.
     */
    @Test
    fun `a bounded runaway answers the reference's StackOverflow term`() {
        val thrown = try {
            compileAndRun(
                "TopLevelBoundedRunaway",
                """
                (= (down ${'$'}n) (if (== ${'$'}n 0) 0 (down (- ${'$'}n 1))))
                !(pragma! max-stack-depth 100)
                !(println! (down 100000000))
                !(println! never-reached)
                """.trimIndent()
            )
            null
        } catch (e: InvocationTargetException) {
            e.targetException
        }
        assertTrue(thrown is MettaError, "expected MettaError, got $thrown")
        assertEquals("(Error (println! (down 100000000)) StackOverflow)", thrown.message)
    }

    /**
     * No bound asked for → the `StackOverflowError` is reported as itself, trace and all. The
     * reference has no bound either unless a program sets one, and a crash we did not promise to
     * catch is the more useful report.
     */
    @Test
    fun `an unbounded runaway stays a StackOverflowError`() {
        val thrown = try {
            compileAndRun(
                "TopLevelUnboundedRunaway",
                """
                (= (down ${'$'}n) (if (== ${'$'}n 0) 0 (down (- ${'$'}n 1))))
                !(println! (down 100000000))
                """.trimIndent()
            )
            null
        } catch (e: InvocationTargetException) {
            e.targetException
        }
        assertTrue(thrown is StackOverflowError, "expected StackOverflowError, got $thrown")
    }

    /** Primitive-valued runs are left alone — nothing is checked, and the class still verifies. */
    @Test
    fun `a program of primitive-valued runs verifies and does not throw`() = compileAndRun(
        "TopLevelPrimitiveRuns",
        """
        !(+ 1 2)
        !(* 2.0 3.0)
        !(< 1 2)
        """.trimIndent()
    )
}
