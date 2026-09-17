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
