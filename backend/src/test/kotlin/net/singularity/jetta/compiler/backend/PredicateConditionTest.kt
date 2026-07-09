package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A non-comparison expression as an `if` condition — a predicate call like `(is-var …)` or a
 * nested `(if …)` — is evaluated to a value and tested for truthiness at runtime
 * (`JettaProgram.isTruthy`: hyperon's True/False symbols, or a grounded Boolean). Before this
 * such a condition hit `generateBooleanExpr`'s `TODO("Op=…")`. Also exercises the `is-var`
 * builtin. Each test invokes `__main`; a failed `!(assertEqual …)` throws.
 */
class PredicateConditionTest : GeneratorTestBase() {

    @Test
    fun `is-var predicate drives an if condition`() {
        compile(
            "IsVarCond.metta",
            $$"""
                (= (classify $x) (if (is-var $x) var notvar))
                !(assertEqual (classify $y) var)
                !(assertEqual (classify a) notvar)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), mc.list().toString())
            val cls = result.toMap().toClasses()["IsVarCond"]!!
            JettaProgram.init("IsVarCond")
            cls.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `a nested if serves as the condition of an outer if`() {
        compile(
            "NestedIfCond.metta",
            $$"""
                !(assertEqual (if (if (== 42 42) True False) yes no) yes)
                !(assertEqual (if (if (== 1 2) True False) yes no) no)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), mc.list().toString())
            val cls = result.toMap().toClasses()["NestedIfCond"]!!
            JettaProgram.init("NestedIfCond")
            cls.getMethod("__main").invoke(null)
        }
    }
}
