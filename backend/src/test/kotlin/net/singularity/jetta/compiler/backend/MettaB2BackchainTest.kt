package net.singularity.jetta.compiler.backend

import kotlin.test.Test

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.runtime.JettaProgram

class MettaB2BackchainTest : GeneratorTestBase() {
    @Test
    fun `match inside function definition`() {
        compile(
            "FrogMatch.metta",
            $$"""
                ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                ; `match` can be used inside equalities, which is typically
                ; used for querying and reasoning over declarative knowledge
                ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                ; Fact
                (Frog Sam)
                (= (frog $x) (match &self (Frog $x) T))
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("FrogMatch")

            // (Frog Sam) is not reduced; it stays in the space as a declaration
            // (frog Sam) should use that declaration and return [T]
            val frogMethod = classes["FrogMatch"]!!.getMethod("frog", Atom::class.java)

            // frog(Sam) -> match finds (Frog Sam) in space, returns T
            val samResult = frogMethod.invoke(null, Symbol("Sam")) as List<*>
            assertEquals(1, samResult.size)
            assertEquals("T", samResult[0].toString())

            // frog(Fritz) -> no (Frog Fritz) in space, returns []
            val fritzResult = frogMethod.invoke(null, Symbol("Fritz")) as List<*>
            assertTrue(fritzResult.isEmpty())
        }
    }
}