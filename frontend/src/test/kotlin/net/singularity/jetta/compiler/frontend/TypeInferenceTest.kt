package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import net.singularity.jetta.compiler.frontend.resolve.messages.CannotInferTypeMessage
import net.singularity.jetta.compiler.frontend.resolve.messages.CannotResolveSymbolMessage
import org.junit.jupiter.api.Test
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TypeInferenceTest : BaseFrontendTest() {
    @Test
    fun inferReturnType() =
        resolve(
            "InferReturnType.metta",
            """
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y 1))
            
            (= (bar) (foo 1 2))   
            """.trimIndent().replace('_', '$')
        ).let { (source, messageCollector) ->
            assertEquals(0, messageCollector.list().size)
            val bar = source.getFunctionDefinition("bar")
            assertNotNull(bar)
            assertNotNull(bar.arrowType)
            assertEquals(ArrowType(GroundedType.INT), bar.arrowType)
        }

    @Test
    fun inferParamType() =
        resolve(
            "InferParamType.metta",
            """
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y 1))
            
            (= (bar _x) (foo _x 2))   
            """.trimIndent().replace('_', '$')
        ).let { (source, messageCollector) ->
            assertEquals(0, messageCollector.list().size)
            val bar = source.getFunctionDefinition("bar")
            assertNotNull(bar)
            assertNotNull(bar.arrowType)
            assertEquals(ArrowType(GroundedType.INT, GroundedType.INT), bar.arrowType)
        }

    @Test
    @Ignore
    // TODO: Select the better option
    // 1) Return message that the type cannot be inferred
    // 2) Infer Int->Int->Int
    fun inferFromContext() =
        resolve(
            "InferFromContext.metta",
            """
            (= (foo _x _y) (+ _x _y 1))
            """.trimIndent().replace('_', '$')
        ).let { (source, messageCollector) ->
            assertEquals(0, messageCollector.list().size)
            val foo = source.getFunctionDefinition("foo")
            assertNotNull(foo)
            assertNotNull(foo.arrowType)
            assertEquals(ArrowType(GroundedType.INT, GroundedType.INT, GroundedType.INT), foo.arrowType)
        }

    @Test
    fun inferSubexpressionType() =
        resolve(
            "InferSubexpressionType.metta",
            """
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y 1))
            
            (= (baz _x) (foo (foo _x 2) (bar _x)))
            (: bar (-> Int Int))
            (= (bar _x) (foo _x 2))  
            """.trimIndent().replace('_', '$')
        ).let { (source, messageCollector) ->
            assertEquals(0, messageCollector.list().size)
            val baz = source.getFunctionDefinition("baz")
            assertNotNull(baz)
            assertNotNull(baz.arrowType)
            assertEquals(ArrowType(GroundedType.INT, GroundedType.INT), baz.arrowType)
        }

    @Test
    fun twoStepsInference() =
        resolve(
            "TwoStepsInference.metta",
            """
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y 1))
            
            (= (baz _x) (foo (foo _x 2) (bar _x)))
            (= (bar _x) (foo _x 2))  
            """.trimIndent().replace('_', '$')
        ).let { (source, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertEquals(0, messageCollector.list().size)
            val baz = source.getFunctionDefinition("baz")
            assertNotNull(baz)
            assertNotNull(baz.arrowType)
            assertEquals(ArrowType(GroundedType.INT, GroundedType.INT), baz.arrowType)
        }

    @Test
    fun inferFact() =
        resolve(
            "Factorial.metta",
            """
            (= (factorial _n)
                (if (== _n 0) 1
                   (* _n (factorial (- _n 1)))))
            """.trimIndent().replace('_', '$')
        ).let { (source, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertEquals(0, messageCollector.list().size)
            val factorial = source.getFunctionDefinition("factorial")
            assertNotNull(factorial)
            assertNotNull(factorial.arrowType)
            assertEquals(ArrowType(GroundedType.INT, GroundedType.INT), factorial.arrowType)
        }

    @Test
    fun cannotInferTypeIfCallableIsNotResolved() =
        resolve(
            "CannotInferTypeIfCallableIsNotResolved.metta",
            """            
            (= (bar _x) (foo _x 2))
            """.trimIndent().replace('_', '$')
        ).let { (_, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
//          FIXME:  assertEquals(2, messageCollector.list().size)
            messageCollector.list()
                .find { it is CannotResolveSymbolMessage }
                .let {
                    assertNotNull(it)
                    assertEquals("foo",(it as CannotResolveSymbolMessage).symbol)
                }
            messageCollector.list()
                .find { it is CannotInferTypeMessage }
                .let {
                    assertNotNull(it)
                    assertEquals("(foo _x 2)".replace('_', '$'),
                        (it as CannotInferTypeMessage).atom.toString())
                }
        }

    private fun ParsedSource.getFunctionDefinition(name: String) =
        code.find { it is FunctionDefinition && it.name == name } as? FunctionDefinition

}