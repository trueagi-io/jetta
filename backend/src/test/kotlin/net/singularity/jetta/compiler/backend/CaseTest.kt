package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `(case S ((P1 B1) …))` desugars (in FunctionRewriter) to a `let` over a right-nested `if`
 * chain: a literal pattern becomes `(if (== $case Pi) Bi rest)`, a variable pattern is the
 * binding catch-all `(let Pi $case Bi)`. Returns the first matching clause's body. (Empty
 * patterns and full structural unification are later steps.) Each test invokes `__main`; a
 * failed `!(assertEqual …)` throws.
 */
class CaseTest : GeneratorTestBase() {

    @Test
    fun `case falls through a non-matching literal to a variable catch-all`() {
        compile(
            "CaseVarCatchall.metta",
            $$"""
                (= (casetest $x)
                   (case $x ((4 42)
                             ($other 44))))
                !(assertEqual (casetest 5) 44)
                !(assertEqual (casetest 4) 42)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), mc.list().toString())
            val cls = result.toMap().toClasses()["CaseVarCatchall"]!!
            JettaProgram.init("CaseVarCatchall")
            cls.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `case selects the matching literal branch`() {
        compile(
            "CaseLiteral.metta",
            $$"""
                (= (f) 42)
                (= (classify) (case (f) ((41 lo) (42 mid) (43 hi))))
                !(assertEqual (classify) mid)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), mc.list().toString())
            val cls = result.toMap().toClasses()["CaseLiteral"]!!
            JettaProgram.init("CaseLiteral")
            cls.getMethod("__main").invoke(null)
        }
    }
}
