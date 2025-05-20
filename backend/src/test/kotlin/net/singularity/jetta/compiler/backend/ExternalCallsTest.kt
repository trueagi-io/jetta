package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalCallsTest : GeneratorTestBase() {
    @Test
    fun `call println with boxed int arg`() =
        compile(
            "Println.metta",
            """
            (println (+ 1 1))
            """.trimIndent().replace('_', '$')
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(1, classes.size)
            classes["Println"]!!.getMethod("__main").invoke(null)
            println()
        }

    @Test
    fun `call println with boxed double arg`() =
        compile(
            "Println.metta",
            """
            (println (+ 1.0 2.0))
            """.trimIndent().replace('_', '$')
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(1, classes.size)
            classes["Println"]!!.getMethod("__main").invoke(null)
            println()
        }

    @Test
    fun random() =
        compile(
            "Random.metta",
            """
            (random)
            """.trimIndent().replace('_', '$')
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(1, classes.size)
            val res = classes["Random"]!!.getMethod("__main").invoke(null)
            assertTrue(res is Double)
            assertTrue(res >= 0.0)
            assertTrue(res <= 1.0)
        }

    @Test
    fun `random with seed`() =
        compile(
            "RandomWithSeed.metta",
            """
            (seed 10L)
            (random)
            """.trimIndent().replace('_', '$')
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(1, classes.size)
            val res = classes["RandomWithSeed"]!!.getMethod("__main").invoke(null)
            assertTrue(res is Double)
            val rand = kotlin.random.Random(10L)
            assertEquals(rand.nextDouble(), res)
        }

    @Test
    fun `generate simple sequence`() =
        compile(
            "GenerateSimple.metta",
            """
            (generate (\ (_x) (+ _x 1.0)) 1.0 3.0 1.0)
            """.trimIndent().replace('_', '$')
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(2, classes.size)
            val res = classes["GenerateSimple"]!!.getMethod("__main").invoke(null)
            println(res)
        }

    @Test
    fun `define coin function`() =
        compile(
            "Coin.metta",
            """
            (: coin (-> Double))
            (= (coin) (if (> (random) 0.5) 1.0 0.0))
            (coin)
            """.trimIndent().replace('_', '$')
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            assertEquals(1, classes.size)
            val res = classes["Coin"]!!.getMethod("__main").invoke(null)
            println(res)
        }
}