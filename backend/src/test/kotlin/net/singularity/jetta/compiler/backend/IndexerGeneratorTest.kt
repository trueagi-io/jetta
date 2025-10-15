package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexerGeneratorTest : GeneratorTestBase() {
    @Test
    fun `exact match`() {
        val generator = IndexerGenerator()
        val expr = Expression(Symbol("Hello"), Symbol("World"))
        val result = generator.generateIndexer(expr)
        writeResult(result)
        val clazz = result.getClass()
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("match", Expression::class.java)
        assertTrue(method.invoke(instance, expr) as Boolean)
        assertFalse(
            method.invoke(
                instance,
                Expression(Symbol("Hello"))
            ) as Boolean
        )
        assertFalse(
            method.invoke(
                instance,
                Expression(Symbol("Hello"), Symbol("Hello"))
            ) as Boolean
        )
        assertFalse(
            method.invoke(
                instance,
                Expression(Symbol("Hello"), Symbol("World"), Symbol("Test"))
            ) as Boolean
        )
    }

    @Test
    fun `exact match with sub-expression`() {
        val generator = IndexerGenerator()
        val expr = Expression(Symbol("Hello"), Expression(Symbol("My"), Symbol("World")))
        val result = generator.generateIndexer(expr)
        writeResult(result)
        val clazz = result.getClass()
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("match", Expression::class.java)
        assertTrue(method.invoke(instance, expr) as Boolean)
        assertFalse(
            method.invoke(
                instance,
                Expression(Symbol("Hello"))
            ) as Boolean
        )
        assertFalse(
            method.invoke(
                instance,
                Expression(Symbol("Hello"), Symbol("Hello"))
            ) as Boolean
        )
        assertFalse(
            method.invoke(
                instance,
                Expression(Symbol("Hello"), Expression(Symbol("World"), Symbol("Test")))
            ) as Boolean
        )
    }
}