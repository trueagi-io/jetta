package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end tests for ordered top-level semantics (Approach 2, "space-query watermark").
 *
 * Each top-level `!`-run sees only the `&self` facts / `:`-declarations declared ABOVE it in source
 * order — hyperon's interleaved model — instead of JeTTa's older "load every fact before any run".
 * So the SAME expression can yield different results before and after a declaration: the real
 * unlock for `b5_types_prelim` and `d4_type_prop`, both unsatisfiable under load-all-first.
 *
 * Mechanism: `FunctionRewriter.mkMain` interleaves a compiler-internal `set-watermark!` step before
 * each run whose visible-fact prefix is short of the total; `JettaProgram.selfAtoms()` clips the
 * `&self` store to that watermark, so `get-type` / `typeCheckError` observe only the visible prefix.
 * See `docs/specs/ordered_top_level_semantics_plan.md`.
 *
 * A green `invoke` IS the assertion (each `!(assertEqual …)` throws from `__main` on a wrong answer).
 */
class OrderedTopLevelTest : GeneratorTestBase() {

    private fun run(name: String, code: String, allowWarnings: Boolean = false) {
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach(::println)
                if (!allowWarnings) assertTrue(messageCollector.list().isEmpty())
                val classes = result.toMap().toClasses()
                JettaProgram.init(name)
                classes[name]!!.getMethod("__main").invoke(null)
            }
    }

    private fun runLenient(name: String, code: String) = run(name, code, allowWarnings = true)

    /**
     * `get-type` of the SAME symbol is `%Undefined%` before its `:` declaration and the declared
     * type after. Under the old load-all-first model the first assert would already see
     * `(: Foo Bar)` and wrongly return `Bar` — this is what the per-run watermark fixes.
     */
    @Test
    fun `get-type sees only declarations above the run`() = runLenient(
        "OrderedGetType",
        """
            !(assertEqual (get-type Foo) %Undefined%)
            (: Foo Bar)
            !(assertEqual (get-type Foo) Bar)
        """.trimIndent()
    )

    /**
     * The `b5` core divergence: `(Add S Z)` reduces to `S` while untyped, but AFTER the `:` types
     * are declared the same expression is a `BadArgType` error (`S` is `(-> Nat Nat)`, not the
     * `Nat` that `Add` expects at position 1). One expression, two answers — impossible under
     * load-all-first. The later well-typed calls still reduce normally.
     */
    @Test
    fun `user function reduces before its types and errors after`() = runLenient(
        "OrderedBadArgType",
        """
            (= (Add ${'$'}x Z) ${'$'}x)
            (= (Add ${'$'}x (S ${'$'}y)) (Add (S ${'$'}x) ${'$'}y))
            !(assertEqual (Add S Z) S)
            (: Z Nat)
            (: S (-> Nat Nat))
            (: Add (-> Nat Nat Nat))
            !(assertEqualToResult
              (Add S Z)
              ((Error (Add S Z) (BadArgType 1 Nat (-> Nat Nat)))))
            !(assertEqual (Add (S Z) Z) (S Z))
        """.trimIndent()
    )
}
