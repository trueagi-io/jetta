package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.ArrowType
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import net.singularity.jetta.compiler.frontend.ir.ResolvedSymbol
import net.singularity.jetta.compiler.frontend.ir.SeqType
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import net.singularity.jetta.runtime.IO
import net.singularity.jetta.runtime.Random
import net.singularity.jetta.runtime.functions.Function1
import org.objectweb.asm.Type
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
            val println = JvmMethod(
                owner = Type.getInternalName(IO::class.java),
                name = "println",
                descriptor = "(Ljava/lang/Object;)V"
            )
            val resolved = ResolvedSymbol(println, null, false)
            context.addSystemFunction(resolved)
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
            val println = JvmMethod(
                owner = Type.getInternalName(IO::class.java),
                name = "println",
                descriptor = "(Ljava/lang/Object;)V"
            )
            val resolved = ResolvedSymbol(println, null, false)
            context.addSystemFunction(resolved)
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
            val random = JvmMethod(
                owner = Type.getInternalName(Random::class.java),
                name = "random",
                descriptor = "()D"
            )
            val resolved = ResolvedSymbol(random, null, false)
            context.addSystemFunction(resolved)
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
            val random = JvmMethod(
                owner = Type.getInternalName(Random::class.java),
                name = "random",
                descriptor = "()D"
            )
            val seed = JvmMethod(
                owner = Type.getInternalName(Random::class.java),
                name = "seed",
                descriptor = "(J)V"
            )
            context.addSystemFunction(ResolvedSymbol(random, null, false))
            context.addSystemFunction(ResolvedSymbol(seed, null, false))
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
            val generate = JvmMethod(
                owner = Type.getInternalName(Random::class.java),
                name = "generate",
                descriptor = "(L${Type.getInternalName(Function1::class.java)};DDD)Ljava/util/List;",
            )
            context.addSystemFunction(ResolvedSymbol(generate,
                ArrowType(ArrowType(GroundedType.DOUBLE, GroundedType.DOUBLE),
                    GroundedType.DOUBLE, GroundedType.DOUBLE,
                    GroundedType.DOUBLE, SeqType(GroundedType.DOUBLE)
                ), true))
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
}