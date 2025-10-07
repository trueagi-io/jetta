package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpaceTest : GeneratorTestBase() {
    @Test
    fun `test match S-expression`() =
        compile(
            "SimpleMatch.metta",
            """
                    ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                    ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                    ; `match` searches for all expressions corresponding to
                    ; the given pattern and produces the output pattern.
                    ; It doesn't search in subexpressions.
                    ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                    ; Some expressions to be matched
                    ((leaf1 leaf2) leaf3)
                    (((leaf0 leaf1) leaf2) leaf3)
                    ; This one contains `((leaf1 leaf2) leaf3)` as a subexpression
                    ; and thus will not be matched
                    (top ((leaf1 leaf2) leaf3))

                    (match &self ((_x leaf2) leaf3) _x)
                    ;  (leaf1 (leaf0 leaf1))
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
            classes["SimpleMatch"]!!.getMethod("__main").invoke(null)
            return@let
        }

    @Test
    fun `test generate match 1`() =
        compile(
            "GenerateMatch1.metta",
            """
                    (match &self (leaf2) _x)
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
            classes["GenerateMatch1"]!!.getMethod("__main").invoke(null)
            return@let
        }
}