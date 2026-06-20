package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultivaluedFunctionTest : GeneratorTestBase() {
    @Test
    fun compileMultiValuedFunction() =
        compile(
            "MultiValuedIntFunction.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            """.trimIndent()
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["MultiValuedIntFunction"]!!.getMethod("foo").invoke(null)
            assertEquals(listOf(1, 2, 3), value)
        }

    @Test
    fun compileOneArgCall() =
        compile(
            "OneMultiValuedArgCall.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (: f (-> Int Int))
            (= (f _x) (+ _x 1))
            
            (@ bar multivalued)
            (: bar (-> Int))
            (= (bar) (f (foo)))  
            """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["OneMultiValuedArgCall"]!!.getMethod("bar").invoke(null)
            assertEquals(listOf(2, 3, 4), value)
        }

    @Test
    fun compileTwoArgsCall() =
        compile(
            "TwoMultiValuedArgsCall.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (: f (-> Int Int Int))
            (= (f _x _y) (+ _x _y))
            
            (@ bar multivalued)
            (: bar (-> Int))
            (= (bar) (f (foo) (foo)))  
            """.trimIndent().replace('_', '$'), mapImpl, flatMapImpl
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["TwoMultiValuedArgsCall"]!!.getMethod("bar").invoke(null)
            assertEquals(listOf(2, 3, 4, 3, 4, 5, 4, 5, 6), value)
        }

    @Test
    fun rewriteNestedExpression() =
        compile(
            "RewriteNestedExpression.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (@ bar multivalued)
            (: bar (-> Int Int))
            (= (bar _x) (+ (foo) (* _x (foo))))
            """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["RewriteNestedExpression"]!!.getMethod("bar", Int::class.java).invoke(null, 2)
            assertEquals(listOf(3, 4, 5, 5, 6, 7, 7, 8, 9), value)
        }

    @Test
    fun rewriteIfCond() =
        compile(
            "RewriteIfCond.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (@ bar multivalued)
            (: bar (-> Int))
            (= (bar) (+ 1 (if (>= (foo) 2) 1 0)))
            """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ).let { (result, messageCollector) ->
            println(result)
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["RewriteIfCond"]!!.getMethod("bar").invoke(null)
            assertEquals(listOf(1, 2, 2), value)
        }

    @Test
    fun rewriteIfCondWithThenBranch() =
        compile(
            "RewriteIfWithThenBranch.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (@ bar multivalued)
            (: bar (-> Int))
            (= (bar) (+ 1 (if (>= (foo) 2) (+ 1 (foo)) 0)))
            """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ).let { (result, messageCollector) ->
            println(result)
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["RewriteIfWithThenBranch"]!!.getMethod("bar").invoke(null)
            assertEquals(listOf(1, 3, 4, 5, 3, 4, 5), value)
        }

    @Test
    fun rewriteIfCondWithElseBranch() =
        compile(
            "RewriteIfWithElseBranch.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (@ bar multivalued)
            (: bar (-> Int))
            (= (bar) (+ 1 (if (>= (foo) 2) 0 (+ 1 (foo)))))
            """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ).let { (result, messageCollector) ->
            println(result)
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["RewriteIfWithElseBranch"]!!.getMethod("bar").invoke(null)
            assertEquals(listOf(3, 4, 5, 1, 1), value)
        }

    @Test
    fun rewriteIfCondWithBothBranches() =
        compile(
            "RewriteIfWithBothBranches.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (@ bar multivalued)
            (: bar (-> Int))
            (= (bar) (+ 1 (if (>= (foo) 2) (+ 2 (foo)) (+ 1 (foo)))))
            """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ).let { (result, messageCollector) ->
            println(result)
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["RewriteIfWithBothBranches"]!!.getMethod("bar").invoke(null)
            assertEquals(listOf(3, 4, 5, 4, 5, 6, 4, 5, 6), value)
        }

    @Test
    fun nonDeterministicFunction() =
        compile(
            "NonDeterministicFunction.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            
            (: f (-> Int Int))
            (= (f _x) (+ _x 1))
            
            (@ bar multivalued)
            (: bar (-> Int))
            (= (bar) (f (foo)))
           
            (@ bar multivalued)
            (: baz (-> Int))
            (= (baz) (bar))
        """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ).let { (result, messageCollector) ->
            println(result)
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["NonDeterministicFunction"]!!.getMethod("baz").invoke(null)
            assertEquals(listOf(2, 3, 4), value)
        }

    // A system multivalued call (`superpose`) in the argument position of an
    // ordinary single-valued function must be lifted into a map?, exactly like a
    // user @multivalued function (cf. compileOneArgCall above). Before the fix,
    // CanonicalFormRewriter only recognised user @multivalued functions, so the
    // superpose bag was handed to `rev` as a raw List and crashed codegen.
    // Mirrors b4_nondeterm's `(rev A (superpose (B C D)))`.
    @Test
    fun liftSystemMultivaluedArgIntoOrdinaryFunction() =
        compile(
            "LiftSuperposeArg.metta",
            """
            (= (rev _x _y) (_y _x))
            !(rev A (superpose (B C D)))
            """.trimIndent().replace('_', '$'),
            mapImpl, flatMapImpl
        ) { context -> registerExternals(context) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach { println(it) }
                assertTrue(messageCollector.list().isEmpty())
                val classes = result.toMap().toClasses()
                JettaProgram.init("LiftSuperposeArg")
                val value = classes["LiftSuperposeArg"]!!.getMethod("__main").invoke(null)
                assertEquals(
                    listOf(
                        Expression(Symbol("B"), Symbol("A")),
                        Expression(Symbol("C"), Symbol("A")),
                        Expression(Symbol("D"), Symbol("A")),
                    ),
                    value
                )
            }
}