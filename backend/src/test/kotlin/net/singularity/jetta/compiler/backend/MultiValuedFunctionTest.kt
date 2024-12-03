package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiValuedFunctionTest : GeneratorTestBase() {
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

    private val mapImpl = JvmMethod(
        owner = "net/singularity/jetta/runtime/UtilKt",
        name = "simpleMap",
        descriptor = "(Ljava/util/function/Function;Ljava/util/List;)Ljava/util/List;",
        signature = "<T:Ljava/lang/Object;R:Ljava/lang/Object;>(Ljava/util/function/Function<TT;TR;>;Ljava/util/List<+TT;>;)Ljava/util/List<TR;>;",
    )

    private val flatMapImpl = JvmMethod(
        owner = "net/singularity/jetta/runtime/UtilKt",
        name = "simpleFlatMap",
        descriptor = "(Ljava/util/function/Function;Ljava/util/List;)Ljava/util/List;",
        signature = "<T:Ljava/lang/Object;R:Ljava/lang/Object;>(Ljava/util/function/Function<TT;Ljava/util/List<TR;>;>;Ljava/util/List<+TT;>;)Ljava/util/List<TR;>;",
    )

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
}