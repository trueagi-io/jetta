package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssertionTest : GeneratorTestBase() {
    @Test
    fun `assertions compile and main class loads`() {
        compile(
            "AssertionCompilation.metta",
            $$"""
                (Frog Sam)
                (= (frog $x) (match &self (Frog $x) T))

                !(assertEqualToResult
                   (Frog Sam)
                   ((Frog Sam)))

                !(assertEqual
                   (frog Sam)
                   T)

                !(assertEqualToResult
                   (frog Fritz)
                   ())
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
            JettaProgram.init("AssertionCompilation")
//            assertEquals(3, classes.size)

            val main = classes["AssertionCompilation"]!!.getMethod("__main")
            main.invoke(null)
        }
    }

    @Test
    fun `assertEqualToResult supports empty expected result marker`() {
        compile(
            "AssertionEmptyExpected.metta",
            $$"""
                (Frog Sam)
                (= (frog $x) (match &self (Frog $x) T))

                !(assertEqualToResult
                   (frog Fritz)
                   ())
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach(::println)
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("AssertionEmptyExpected")
            classes["AssertionEmptyExpected"]!!.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `assertEqualToResult supports metta-style singleton expected result`() {
        compile(
            "AssertionSingletonExpected.metta",
            $$"""
                (Frog Sam)

                !(assertEqualToResult
                   (Frog Sam)
                   ((Frog Sam)))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach(::println)
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("AssertionSingletonExpected")
            classes["AssertionSingletonExpected"]!!.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `assertEqual evaluates both arguments`() {
        compile(
            "AssertionEqualEvaluation.metta",
            $$"""
                (Frog Sam)
                (= (frog $x) (match &self (Frog $x) T))

                !(assertEqual
                   (frog Sam)
                   T)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach(::println)
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("AssertionEqualEvaluation")
            classes["AssertionEqualEvaluation"]!!.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `assertEqual compares symbolic structures`() {
        compile(
            "AssertionSymbolicStructure.metta",
            $$"""
                (= (explain)
                   ((mortal Plato)
                     proven-by
                     ((human Plato))))

                !(assertEqual
                   (explain)
                   ((mortal Plato)
                     proven-by
                     ((human Plato))))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach(::println)
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("AssertionSymbolicStructure")
            classes["AssertionSymbolicStructure"]!!.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `assertEqualToResult with simple call`() {
        compile(
            "AssertionSimpleCall.metta",
            $$"""
                (: foo (-> Int))
                (= (foo) (+ 1 1))
                !(assertEqualToResult
                   (foo)
                   2)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach(::println)
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("AssertionSimpleCall")
            classes["AssertionSimpleCall"]!!.getMethod("__main").invoke(null)
        }
    }
}