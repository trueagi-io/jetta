package net.singularity.jetta.compiler.backend

import kotlin.test.Test

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
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

    @Test
    fun `backward chaining deduction - Plato is mortal`() {
        compile(
            "BackchainDeduction.metta",
            $$"""
                ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                ; The result of matching is also chained
                ; Example from OpenCog Classic wiki on PLN Backward Chaining
                ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                ; Some facts in the knowledge base
                (Evaluation (philosopher Plato))
                (Evaluation (likes-to-wrestle Plato))

                ; An implication rule
                (Implication
                   (And (Evaluation (philosopher $x))
                        (Evaluation (likes-to-wrestle $x)))
                   (Evaluation (human $x)))

                ; Another implication rule
                (Implication
                   (Evaluation (human $x))
                   (Evaluation (mortal $x)))

                ; Deduction case when the desired evaluation is present in
                ; the knowledge base
                (= (deduce (Evaluation ($P $x)))
                   (match &self (Evaluation ($P $x)) T))

                ; Deduction case when the desired evaluation is the result
                ; of an implication, which implies a recursion
                (= (deduce (Evaluation ($P $x)))
                   (match &self
                     (Implication $a (Evaluation ($P $x)))
                     (deduce $a)))

                ; Deduction case for generic "And" expressions;
                ; also recursive
                (= (deduce (And $a $b))
                   (And (deduce $a) (deduce $b)))

                ; True & True = True
                (= (And T T) T)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("BackchainDeduction")

            val deduceMethod = classes["BackchainDeduction"]!!.getMethod("deduce", Atom::class.java)

            // deduce(Evaluation(mortal Plato)) should chain through
            // implication rules and return [T]
            val mortalPlato = Expression(
                Symbol("Evaluation"),
                Expression(Symbol("mortal"), Symbol("Plato"))
            )
            val deduceResult = deduceMethod.invoke(null, mortalPlato) as List<*>
            assertTrue(deduceResult.isNotEmpty())
            assertTrue(deduceResult.any { it.toString() == "T" })
        }
    }

    @Test
    fun `backward chaining deduction - deduce who is mortal`() {
        compile(
            "BackchainWho.metta",
            $$"""
                ; Facts
                (Evaluation (philosopher Plato))
                (Evaluation (likes-to-wrestle Plato))

                ; Implication rules
                (Implication
                   (And (Evaluation (philosopher $x))
                        (Evaluation (likes-to-wrestle $x)))
                   (Evaluation (human $x)))

                (Implication
                   (Evaluation (human $x))
                   (Evaluation (mortal $x)))

                ; Deduction rules
                (= (deduce (Evaluation ($P $x)))
                   (match &self (Evaluation ($P $x)) T))

                (= (deduce (Evaluation ($P $x)))
                   (match &self
                     (Implication $a (Evaluation ($P $x)))
                     (deduce $a)))

                (= (deduce (And $a $b))
                   (And (deduce $a) (deduce $b)))

                (= (And T T) T)

                ; Helper to extract value when deduction succeeds
                (= (ift T $then) $then)

                ; Top-level query: who is mortal?
                ; $x should be unified with Plato during deduction

                !(ift (deduce (Evaluation (mortal $x))) $x)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("BackchainWho")

            // The top-level expression (ift (deduce (Evaluation (mortal $x))) $x)
            // should evaluate to [Plato] — $x gets unified with Plato
            // during the backward chaining deduction
            val mainResult = classes["BackchainWho"]!!.getMethod("__main").invoke(null)
            println("mainResult: $mainResult")
            val results = mainResult as List<*>
            assertTrue(results.isNotEmpty(), "Expected Plato but got empty result")
            assertTrue(
                results.any { it.toString() == "Plato" },
                "Expected Plato in results but got: $results"
            )
        }
    }
}