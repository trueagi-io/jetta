package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MettaA1SymbolsTest : GeneratorTestBase() {
    // some tests borrowed from https://github.com/trueagi-io/hyperon-experimental/blob/main/python/tests/scripts/a1_symbols.metta
    @Test
    fun `match does not descend into subexpressions`() =
        compile(
            "NoSubExprMatch.metta",
            $$"""
                ; (A B) is a top-level expression
                (A B)
                ; (C (A B)) contains (A B) as a subexpression — should NOT match
                (C (A B))
                (match &self (A $x) $x)
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val r = classes["NoSubExprMatch"]!!.getMethod("__main").invoke(null) as List<*>
            assertEquals(1, r.size)
            assertEquals("B", r[0].toString())
        }

    @Test
    fun `match with output template expression`() =
        compile(
            "OutputTemplate.metta",
            $$"""
                (color red)
                (color green)
                (color blue)
                (match &self (color $x) (painted $x))
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val r = classes["OutputTemplate"]!!.getMethod("__main").invoke(null) as List<*>
            assertEquals(3, r.size)
            val strings = r.map { it.toString() }.toSet()
            assertTrue(strings.contains("(painted red)"))
            assertTrue(strings.contains("(painted green)"))
            assertTrue(strings.contains("(painted blue)"))
        }

    @Test
    fun `match with two variables in pattern`() =
        compile(
            "TwoVarsMatch.metta",
            $$"""
                (edge A B)
                (edge B C)
                (edge C D)
                (match &self (edge $x $y) ($y $x))
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val r = classes["TwoVarsMatch"]!!.getMethod("__main").invoke(null) as List<*>
            assertEquals(3, r.size)
            val strings = r.map { it.toString() }.toSet()
            assertTrue(strings.contains("(B A)"))
            assertTrue(strings.contains("(C B)"))
            assertTrue(strings.contains("(D C)"))
        }

    @Test
    fun `match returns empty when no pattern matches`() =
        compile(
            "NoMatch.metta",
            $$"""
                (A B)
                (C D)
                (match &self (X $y) $y)
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val r = classes["NoMatch"]!!.getMethod("__main").invoke(null) as List<*>
            assertTrue(r.isEmpty())
        }

    @Test
    fun `match deeply nested expressions`() =
        compile(
            "DeepNested.metta",
            $$"""
                (a (b (c d)))
                (a (b (c e)))
                (a (b (f g)))
                (match &self (a (b (c $x))) $x)
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val r = classes["DeepNested"]!!.getMethod("__main").invoke(null) as List<*>
            assertEquals(2, r.size)
            val strings = r.map { it.toString() }.toSet()
            assertEquals(setOf("d", "e"), strings)
        }

    @Test
    fun `match with only symbols no variables in pattern`() =
        compile(
            "ExactMatch.metta",
            $$"""
                (isa cat animal)
                (isa dog animal)
                (isa car vehicle)
                (match &self (isa cat animal) found)
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val r = classes["ExactMatch"]!!.getMethod("__main").invoke(null) as List<*>
            assertEquals(1, r.size)
            assertEquals("found", r[0].toString())
        }

    @Test
    fun `match with variable in first position`() =
        compile(
            "VarFirstPos.metta",
            $$"""
                (red apple)
                (green apple)
                (yellow banana)
                (match &self ($x apple) $x)
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val r = classes["VarFirstPos"]!!.getMethod("__main").invoke(null) as List<*>
            assertEquals(2, r.size)
            val strings = r.map { it.toString() }.toSet()
            assertEquals(setOf("red", "green"), strings)
        }

    @Test
    fun `match with all variables captures everything`() =
        compile(
            "AllVarsMatch.metta",
            $$"""
                (A B)
                (C D)
                (E F)
                (match &self ($x $y) ($x $y))
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val r = classes["AllVarsMatch"]!!.getMethod("__main").invoke(null) as List<*>
            assertEquals(3, r.size)
            val strings = r.map { it.toString() }.toSet()
            assertTrue(strings.contains("(A B)"))
            assertTrue(strings.contains("(C D)"))
            assertTrue(strings.contains("(E F)"))
        }

    @Test
    fun `match with nested pattern and output template`() =
        compile(
            "NestedTemplate.metta",
            $$"""
                ((leaf1 leaf2) leaf3)
                (((leaf0 leaf1) leaf2) leaf3)
                (top ((leaf1 leaf2) leaf3))
                (match &self (($x leaf2) leaf3) (found $x))
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val r = classes["NestedTemplate"]!!.getMethod("__main").invoke(null) as List<*>
            assertEquals(2, r.size)
            val strings = r.map { it.toString() }.toSet()
            assertTrue(strings.contains("(found leaf1)"))
            assertTrue(strings.any { it.contains("found") && it.contains("leaf0") && it.contains("leaf1") })
        }

}