package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `|->` is MeTTa-TS surface syntax for a lambda; the grammar maps it to the same LAMBDA
 * token as `\`, so it produces identical IR and reuses the whole lambda pipeline. This pins
 * `|->` as equivalent to `\` for a direct application. (Corpus programs using `|->` — foldall
 * etc. — need additional higher-order builtins to pass; this only covers the lambda syntax.)
 */
class PipeArrowLambdaTest : GeneratorTestBase() {

    @Test
    fun `pipe-arrow lambda applies like a backslash lambda`() {
        compile(
            "PipeArrowLambda.metta",
            $$"""
                !(assertEqual ((|-> ($x $y) (+ $x $y)) 2 3) 5)
                !(assertEqual ((\ ($x $y) (+ $x $y)) 2 3) 5)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), mc.list().toString())
            val cls = result.toMap().toClasses()["PipeArrowLambda"]!!
            JettaProgram.init("PipeArrowLambda")
            cls.getMethod("__main").invoke(null)
        }
    }
}
