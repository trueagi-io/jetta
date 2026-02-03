package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.runtime.space.HashMapBindingsImpl
import net.singularity.jetta.runtime.space.Indexer
import net.singularity.jetta.runtime.space.IndexerImpl
import net.singularity.jetta.runtime.space.atoms.SSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexerImplTest {
    fun createIndexer(expression: Expression): Indexer = IndexerImpl(expression)

    @Test
    fun `exact match`() {
        val expr = Expression(Symbol("Hello"), Symbol("World"))
        val indexer = createIndexer(expr)
        with(HashMapBindingsImpl()) {
            assertTrue(indexer.match(expr, this))
            assertEquals(0, size())
        }
        with(HashMapBindingsImpl()) {
            assertFalse(indexer.match(Expression(Symbol("Hello")), this))
            assertEquals(0, size())
        }
        with(HashMapBindingsImpl()) {
            assertFalse(indexer.match(Expression(Symbol("Hello"), Symbol("Hello")), this))
            assertEquals(0, size())
        }
        with(HashMapBindingsImpl()) {
            assertFalse(indexer.match(Expression(Symbol("Hello"), Symbol("World"), Symbol("Test")), this))
            assertEquals(0, size())
        }
    }

    @Test
    fun `exact match subexpression`() {
        val expr = Expression(
            Symbol("Hello"),
            Expression(
                Symbol("My"),
                Symbol("World")
            )
        )
        val indexer = createIndexer(expr)
        with(HashMapBindingsImpl()) {
            assertTrue(indexer.match(expr, this))
            assertEquals(0, size())
        }
        with(HashMapBindingsImpl()) {
            assertFalse(indexer.match(Expression(Symbol("Hello")), this))
            assertEquals(0, size())
        }
        with(HashMapBindingsImpl()) {
            assertFalse(
                indexer.match(
                    Expression(Symbol("Hello"), Symbol("Hello")), this
                )
            )
            assertEquals(0, size())
        }
        with(HashMapBindingsImpl()) {
            assertFalse(
                indexer.match(
                    Expression(
                        Symbol("Hello"),
                        Expression(Symbol("World"), Symbol("Test"))
                    ), this
                )
            )
            assertEquals(0, size())
        }
    }

    @Test
    fun `match with single variable`() {
        val expr = Expression(Symbol("Hello"), Variable("x"))
        val indexer = createIndexer(expr)
        
        with(HashMapBindingsImpl()) {
            assertTrue(indexer.match(Expression(Symbol("Hello"), Symbol("World")), this))
            assertEquals(1, size())
            assertEquals(SSymbol("World"), get("x"))
        }
        
        with(HashMapBindingsImpl()) {
            assertFalse(indexer.match(Expression(Symbol("Goodbye"), Symbol("World")), this))
            assertEquals(0, size())
        }
    }

    @Test
    fun `match with multiple variables`() {
        val expr = Expression(Symbol("pair"), Variable("x"), Variable("y"))
        val indexer = createIndexer(expr)
        
        with(HashMapBindingsImpl()) {
            assertTrue(indexer.match(Expression(Symbol("pair"), Symbol("first"), Symbol("second")), this))
            assertEquals(2, size())
            assertEquals(SSymbol("first"), get("x"))
            assertEquals(SSymbol("second"), get("y"))
        }
    }

    @Test
    fun `match with variable in subexpression`() {
        val expr = Expression(
            Symbol("outer"),
            Expression(Symbol("inner"), Variable("x"))
        )
        val indexer = createIndexer(expr)
        
        with(HashMapBindingsImpl()) {
            assertTrue(
                indexer.match(
                    Expression(
                        Symbol("outer"),
                        Expression(Symbol("inner"), Symbol("value"))
                    ),
                    this
                )
            )
            assertEquals(1, size())
            assertEquals(SSymbol("value"), get("x"))
        }
    }

    @Test
    fun `match with same variable appearing twice`() {
        val expr = Expression(Symbol("eq"), Variable("x"), Variable("x"))
        val indexer = createIndexer(expr)
        
        with(HashMapBindingsImpl()) {
            assertTrue(indexer.match(Expression(Symbol("eq"), Symbol("same"), Symbol("same")), this))
            assertEquals(1, size())
            assertEquals(SSymbol("same"), get("x"))
        }
        
        with(HashMapBindingsImpl()) {
            assertFalse(indexer.match(Expression(Symbol("eq"), Symbol("first"), Symbol("second")), this))
        }
    }

    @Test
    fun `match expression with all variables`() {
        val expr = Expression(Variable("op"), Variable("x"), Variable("y"))
        val indexer = createIndexer(expr)
        
        with(HashMapBindingsImpl()) {
            assertTrue(indexer.match(Expression(Symbol("add"), Symbol("a"), Symbol("b")), this))
            assertEquals(3, size())
            assertEquals(SSymbol("add"), get("op"))
            assertEquals(SSymbol("a"), get("x"))
            assertEquals(SSymbol("b"), get("y"))
        }
    }

    @Test
    fun `match with variable as operator`() {
        val expr = Expression(Variable("op"), Symbol("arg1"), Symbol("arg2"))
        val indexer = createIndexer(expr)
        
        with(HashMapBindingsImpl()) {
            assertTrue(indexer.match(Expression(Symbol("func"), Symbol("arg1"), Symbol("arg2")), this))
            assertEquals(1, size())
            assertEquals(SSymbol("func"), get("op"))
        }
        
        with(HashMapBindingsImpl()) {
            assertFalse(indexer.match(Expression(Symbol("func"), Symbol("wrong"), Symbol("arg2")), this))
        }
    }

    @Test
    fun `match with variable as operator and arguments`() {
        val expr = Expression(Variable("f"), Variable("x"))
        val indexer = createIndexer(expr)
        
        with(HashMapBindingsImpl()) {
            assertTrue(indexer.match(Expression(Symbol("identity"), Symbol("value")), this))
            assertEquals(2, size())
            assertEquals(SSymbol("identity"), get("f"))
            assertEquals(SSymbol("value"), get("x"))
        }
    }

    @Test
    fun `match with same variable as operator and argument`() {
        val expr = Expression(Variable("x"), Variable("x"))
        val indexer = createIndexer(expr)
        
        with(HashMapBindingsImpl()) {
            assertTrue(indexer.match(Expression(Symbol("same"), Symbol("same")), this))
            assertEquals(1, size())
            assertEquals(SSymbol("same"), get("x"))
        }
        
        with(HashMapBindingsImpl()) {
            assertFalse(indexer.match(Expression(Symbol("first"), Symbol("second")), this))
        }
    }

    @Test
    fun `match nested expression with variable operator`() {
        val expr = Expression(
            Symbol("outer"),
            Expression(Variable("inner"), Variable("x"))
        )
        val indexer = createIndexer(expr)
        
        with(HashMapBindingsImpl()) {
            assertTrue(
                indexer.match(
                    Expression(
                        Symbol("outer"),
                        Expression(Symbol("plus"), Symbol("value"))
                    ),
                    this
                )
            )
            assertEquals(2, size())
            assertEquals(SSymbol("plus"), get("inner"))
            assertEquals(SSymbol("value"), get("x"))
        }
    }

    @Test
    fun `match complex pattern with variables in multiple positions`() {
        val expr = Expression(
            Variable("outer"),
            Expression(Variable("inner"), Variable("x")),
            Variable("y")
        )
        val indexer = createIndexer(expr)
        
        with(HashMapBindingsImpl()) {
            assertTrue(
                indexer.match(
                    Expression(
                        Symbol("apply"),
                        Expression(Symbol("add"), Symbol("1")),
                        Symbol("2")
                    ),
                    this
                )
            )
            assertEquals(4, size())
            assertEquals(SSymbol("apply"), get("outer"))
            assertEquals(SSymbol("add"), get("inner"))
            assertEquals(SSymbol("1"), get("x"))
            assertEquals(SSymbol("2"), get("y"))
        }
    }

    @Test
    fun `match nested expressions with variables`() {
        val expr = Expression(
            Symbol("outer"),
            Variable("x"),
            Expression(Symbol("inner"), Variable("y"))
        )
        val indexer = createIndexer(expr)
        
        with(HashMapBindingsImpl()) {
            assertTrue(
                indexer.match(
                    Expression(
                        Symbol("outer"),
                        Symbol("first"),
                        Expression(Symbol("inner"), Symbol("second"))
                    ),
                    this
                )
            )
            assertEquals(2, size())
            assertEquals(SSymbol("first"), get("x"))
            assertEquals(SSymbol("second"), get("y"))
        }
    }

    @Test
    fun `no match when same variable binds to different values`() {
        val expr = Expression(Symbol("pair"), Variable("x"), Variable("x"))
        val indexer = createIndexer(expr)

        with(HashMapBindingsImpl()) {
            assertFalse(indexer.match(Expression(Symbol("pair"), Symbol("a"), Symbol("b")), this))
            assertEquals(0, size())
            // Bindings should be empty or contain inconsistent state - no match occurred
        }
    }
}