package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LambdaTest : GeneratorTestBase() {
    @Test
    fun simple() =
        compile(
            "Lambda.metta",
            """
                (: foo (-> Int Int (-> Int Int Int) Int))
                (= (foo _x _y _f) (_f _x _y))
                (foo 10 20 (\ (_x _y) (+ _x _y)))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(2, classes.size)
            val value = classes["Lambda"]!!.getMethod("__main").invoke(null)
            assertEquals(30, value)
        }

    @Test
    fun capturingVariable() =
        compile(
            "CapturingVariable.metta",
            """
                (: foo (-> Int Int (-> Int Int Int) Int))
                (= (foo _x _y _f) (_f _x _y))
                (: bar (-> Int Int))
                (= (bar _z)
                   (foo 10 20 (\ (_x _y) (+ _x _y _z)))
                )
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["CapturingVariable"]!!.getMethod("bar", Int::class.java).invoke(null, 10)
            assertEquals(40, value)
        }

    @Test
    fun capturingTwoVariables() =
        compile(
            "CapturingTwoVariables.metta",
            """
                (: foo (-> Int Int (-> Int Int Int) Int))
                (= (foo _x _y _f) (_f _x _y))
                (: bar (-> Int Int Int))
                (= (bar _z _w)
                   (foo 10 20 (\ (_x _y) (+ _x _y _z _w)))
                )
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["CapturingTwoVariables"]!!
                .getMethod("bar", Int::class.java, Int::class.java)
                .invoke(null, 10, 11)
            assertEquals(51, value)
        }

    @Test
    fun nestedLambdas() =
        compile(
            "NestedLambdas.metta",
            """
                (: foo (-> Int Int (-> Int Int Int) Int))
                (= (foo _x _y _f) (_f _x _y))
                (: bar (-> Int Int))
                (= (bar _z)
                   (foo 10 20 (\ (_x _y) (+ _x _y _z (foo 10 20 (\ (_k _v) (+ _k _v)))
                   )))
                )
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["NestedLambdas"]!!
                .getMethod("bar", Int::class.java)
                .invoke(null, 5)
            assertEquals(65, value)
        }

    @Test
    fun nestedLambdasCapturing() =
        compile(
            "NestedLambdasCapturing.metta",
            """
                (: foo (-> Int Int (-> Int Int Int) Int))
                (= (foo _x _y _f) (_f _x _y))
                (: bar (-> Int Int))
                (= (bar _z)
                   (foo 10 20 (\ (_x _y) (+ _x _y _z (foo 10 20 (\ (_k _v) (+ _k _v _x)))
                   )))
                )
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["NestedLambdasCapturing"]!!
                .getMethod("bar", Int::class.java)
                .invoke(null, 5)
            assertEquals(75, value)
        }

    @Test
    fun nestedLambdasCapturingParentVariable() =
        compile(
            "NestedLambdasCapturingParentVariable.metta",
            """
                (: foo (-> Int Int (-> Int Int Int) Int))
                (= (foo _x _y _f) (_f _x _y))
                (: bar (-> Int Int))
                (= (bar _z)
                   (foo 10 20 (\ (_x _y) (+ _x _y (foo 10 20 (\ (_x _y) (+ _x _y _z)))
                   )))
                )
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["NestedLambdasCapturingParentVariable"]!!
                .getMethod("bar", Int::class.java)
                .invoke(null, 5)
            assertEquals(65, value)
        }

    @Test
    fun lambda3() =
        compile(
            "Lambda.metta",
            """
                (: foo (-> Int Int (-> Int Int Int Int) Int))
                (= (foo _x _y _f) (_f _x _y _x))
                (foo 10 20 (\ (_x _y _z) (+ _x _y _z)))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["Lambda"]!!.getMethod("__main").invoke(null)
            assertEquals(40, value)
        }

    @Test
    fun passFunction() =
        compile(
            "PassFunction.metta",
            """
                (: foo (-> Int Int (-> Int Int Int) Int))
                (= (foo _x _y _f) (_f _x _y))
                (: bar (-> Int Int Int))
                (= (bar _x _y) (+ _x _y))
                (foo 10 20 bar)
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(2, classes.size)
            val value = classes["PassFunction"]!!.getMethod("__main").invoke(null)
            assertEquals(30, value)
        }
}