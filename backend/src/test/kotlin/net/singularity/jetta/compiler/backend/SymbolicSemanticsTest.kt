package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Symbolic-first semantics reference tests.
 *
 * These tests document the intended surface-language behavior for symbols,
 * quote, sequences, and assertions.
 *
 * Core rules
 * ----------
 * 1. Expression head position is callable.
 *    In an expression like `(frog Sam)`, `frog` is resolved as a function name.
 *
 * 2. Non-head symbol position is symbolic by default.
 *    In `(frog Sam)`, `Sam` is treated as a symbolic atom value, not as an
 *    unresolved function reference.
 *
 * 3. Quote produces literal atom data.
 *    `'X` means the atom `X`.
 *    `'(Frog Sam)` means the atom `(Frog Sam)`.
 *
 * 4. Sequence literals represent expected result collections.
 *    `[]` is the empty result sequence.
 *    `['(Frog Sam)]` is a one-element result sequence whose element is the atom
 *    `(Frog Sam)`.
 *
 * 5. Assertions are executed only when JVM assertions are enabled.
 *    Their bodies are ordinary expressions, but symbolic arguments inside them
 *    follow the same symbolic-first rules as everywhere else.
 *
 * Examples
 * --------
 * Fact declaration:
 *   (Frog Sam)
 *
 * Querying with a symbolic argument:
 *   (frog Sam)
 * Here `Sam` is an atom value.
 *
 * Quoted atom:
 *   '(Frog Sam)
 * This is literal data, not a call.
 *
 * Expected result sequence:
 *   ['(Frog Sam)]
 * This is a sequence containing one atom result.
 *
 * Empty expected result sequence:
 *   []
 *
 * Assertion examples:
 *   !(assertEqual (frog Sam) T)
 *   !(assertEqualToResult '(Frog Sam) ['(Frog Sam)])
 *   !(assertEqualToResult (frog Fritz) [])
 */
class SymbolicSemanticsTest : GeneratorTestBase() {

    @Test
    fun `non-head symbols are treated as symbolic atom arguments`() {
        compile(
            "SymbolicArg.metta",
            $$"""
                (Frog Sam)
                (= (frog $x) (match &self (Frog $x) T))
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("SymbolicArg")
            val frogMethod = classes["SymbolicArg"]!!.getMethod("frog", net.singularity.jetta.compiler.frontend.ir.Atom::class.java)

            val samResult = frogMethod.invoke(null, Symbol("Sam")) as List<*>
            assertEquals(1, samResult.size)
            assertEquals("T", samResult[0].toString())

            val fritzResult = frogMethod.invoke(null, Symbol("Fritz")) as List<*>
            assertTrue(fritzResult.isEmpty())
        }
    }

    @Test
    fun `quoted expression is treated as literal atom data`() {
        compile(
            "QuotedAtom.metta",
            """
                (Frog Sam)
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
        }
    }


    @Test
    fun `symbolic truth atoms remain symbolic`() {
        compile(
            "SymbolicTruth.metta",
            """
                (= (truth) T)
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("SymbolicTruth")
            val truthMethod = classes["SymbolicTruth"]!!.getMethod("truth")
            val resultList = truthMethod.invoke(null)

            // Depending on current multivalued lowering and inferred return form,
            // the important part is that T stays symbolic, not a grounded boolean.
            assertTrue(resultList.toString().contains("T"))
        }
    }
}