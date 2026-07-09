package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `match` template that CONTAINS reducible calls is evaluated once per binding, not
 * quoted as inert data. `FunctionRewriter.rewriteMatchCall` lifts such a template to
 * `map? (\ $__t. TEMPLATE) (match &self (quote PATTERN) (quote $__t))`: the outer match
 * installs every pattern-variable binding into the Matcher (BoundAtom snapshot →
 * `simpleMap`'s `putAll`), the lambda body reduces the template with its free variables
 * resolved from those bindings, and `add-atom`/`remove-atom` (and the `match` pattern
 * itself) resolve their argument against the Matcher first (`Matcher.resolveDeep`).
 *
 * This is the `hide (match … side-effects)` idiom (PeTTa's matchnested2). Each test
 * invokes `__main`; a failed `!(assertEqual …)` throws.
 */
class MatchTemplateReductionTest : GeneratorTestBase() {

    @Test
    fun `a match template that is a tuple of add-atoms runs every call with bound vars`() {
        compile(
            "MatchTupleTemplate.metta",
            $$"""
                (= (hide $x) ())
                !(add-atom &self (edge a b))
                !(hide (match &self (edge $x $y)
                              ((add-atom &self (L $x)) (add-atom &self (R $y)))))
                !(assertEqual (collapse (match &self (L $n) $n)) (a))
                !(assertEqual (collapse (match &self (R $n) $n)) (b))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), mc.list().toString())
            val cls = result.toMap().toClasses()["MatchTupleTemplate"]!!
            JettaProgram.init("MatchTupleTemplate")
            cls.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `a conjunction match with a side-effecting template chains multiple bound vars`() {
        compile(
            "MatchConjTemplate.metta",
            $$"""
                (= (hide $x) ())
                !(add-atom &self (edge a b))
                !(add-atom &self (edge b c))
                !(hide (match &self (, (edge $1 $2) (edge $2 $3))
                              ((add-atom &self (path $1 $2 $3))
                               (remove-atom &self (edge $1 $2)))))
                !(assertEqual (collapse (match &self (path $x $y $z) (path $x $y $z)))
                              ((path a b c)))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), mc.list().toString())
            val cls = result.toMap().toClasses()["MatchConjTemplate"]!!
            JettaProgram.init("MatchConjTemplate")
            cls.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `a match template that is pure data is still returned unreduced`() {
        // Guard against over-eager lifting: a template with NO reducible call must keep the
        // quote path (return the substituted data per binding), unchanged by the lift.
        compile(
            "MatchDataTemplate.metta",
            $$"""
                !(add-atom &self (edge a b))
                !(add-atom &self (edge c d))
                !(assertEqual (msort (collapse (match &self (edge $x $y) (pair $x $y))))
                              ((pair a b) (pair c d)))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), mc.list().toString())
            val cls = result.toMap().toClasses()["MatchDataTemplate"]!!
            JettaProgram.init("MatchDataTemplate")
            cls.getMethod("__main").invoke(null)
        }
    }
}
