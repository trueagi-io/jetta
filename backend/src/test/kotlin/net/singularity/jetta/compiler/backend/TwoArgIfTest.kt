package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The 2-argument `(if cond then)` sugar (hyperon: "then when cond, else Empty") and a bare
 * boolean literal used directly as a condition. `FunctionRewriter` pads the missing else
 * with `()`; `generateIf` homogenizes the arms to Atom (the primitive `then` wrapped in
 * Grounded so it merges with the object `()` else without a JVM VerifyError); and
 * `generateBooleanExpr` emits `True`/`False` as the int the branch test expects rather than
 * a Symbol object. Each test invokes `__main`; a failed `!(assertEqual …)` throws.
 */
class TwoArgIfTest : GeneratorTestBase() {

    @Test
    fun `two-arg if returns the then-branch when the condition holds`() {
        compile(
            "TwoArgIfTrue.metta",
            $$"""
                !(assertEqual (if True 42) 42)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), mc.list().toString())
            val cls = result.toMap().toClasses()["TwoArgIfTrue"]!!
            JettaProgram.init("TwoArgIfTrue")
            cls.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `a bare boolean literal condition selects the right three-arg branch`() {
        compile(
            "BareBoolCond.metta",
            $$"""
                !(assertEqual (if True 42 99) 42)
                !(assertEqual (if False 42 99) 99)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), mc.list().toString())
            val cls = result.toMap().toClasses()["BareBoolCond"]!!
            JettaProgram.init("BareBoolCond")
            cls.getMethod("__main").invoke(null)
        }
    }
}
