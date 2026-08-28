package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `context-space` and `_minimal-foldl-atom` — the two grounded primitives the reference
 * `foldl-atom` stands on, and through it `add-atoms` / `add-reducts` / `for-each-in-atom`.
 *
 * The fold is where a TEMPLATE meets evaluation: `$a` and `$b` are variables naming the two slots
 * of the operation, so `(+ $a $b)` has to arrive as data and be substituted into per step, not
 * evaluated as arithmetic over unbound variables.
 *
 * Each program asserts with `!(assertEqual …)`, which throws from `__main` on a wrong answer, so a
 * green `invoke` IS the assertion. Every case was first checked to FAIL against a wrong
 * expectation.
 *
 * NOTE the placeholder: this file writes `~a` for `$a` and substitutes it in [v], rather than the
 * `_`-for-`$` convention used elsewhere — the primitive under test is *named* `_minimal-foldl-atom`,
 * and a blanket `_` → `$` would rewrite its name.
 */
class MinimalFoldTest : GeneratorTestBase() {

    private fun run(name: String, code: String) {
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach(::println)
                assertTrue(messageCollector.list().isEmpty(), "no diagnostics expected")
                val classes = result.toMap().toClasses()
                JettaProgram.init(name)
                classes[name]!!.getMethod("__main").invoke(null)
            }
    }

    private fun String.v() = replace('~', '$')

    /** `context-space` names the space of the running program, in the form a space argument takes. */
    @Test
    fun `context-space names the running program's space`() {
        run(
            "CtxSpaceName",
            """
            !(assertEqual (context-space) CtxSpaceName)
            """.trimIndent()
        )
    }

    /** …and it is accepted where a space is expected, which is the whole point of returning it. */
    @Test
    fun `context-space drives a match`() {
        run(
            "CtxSpaceMatch",
            """
            (fact Ann)
            !(assertEqual (match (context-space) (fact ~who) ~who) Ann)
            """.trimIndent().v()
        )
    }

    /** The probe shape: sum a tuple. */
    @Test
    fun `the fold sums a tuple`() {
        run(
            "FoldSum",
            """
            !(assertEqual (_minimal-foldl-atom (1 2 3) 0 ~a ~b (+ ~a ~b) &self) 6)
            """.trimIndent().v()
        )
    }

    /**
     * A NON-COMMUTATIVE operation pins both the direction of the fold and which slot is which:
     * left-fold with `$a` = accumulator and `$b` = element gives `((0 - 1) - 2) = -3`. Swap either
     * and the answer changes, so this is the case that catches a mirrored implementation.
     */
    @Test
    fun `the fold is left-associative with the accumulator in the first slot`() {
        run(
            "FoldOrder",
            """
            !(assertEqual (_minimal-foldl-atom (1 2) 0 ~a ~b (- ~a ~b) &self) -3)
            """.trimIndent().v()
        )
    }

    /** An empty tuple never applies the operation and yields the init unchanged. */
    @Test
    fun `an empty tuple folds to the init value`() {
        run(
            "FoldEmpty",
            """
            !(assertEqual (_minimal-foldl-atom () 7 ~a ~b (+ ~a ~b) &self) 7)
            """.trimIndent().v()
        )
    }

    /** A step that yields several results FORKS the fold — the accumulator is a bag. */
    @Test
    fun `a multivalued step forks the fold`() {
        run(
            "FoldFork",
            """
            !(assertEqualToResult (_minimal-foldl-atom (2) 1 ~a ~b (superpose (~a ~b)) &self) (1 2))
            """.trimIndent().v()
        )
    }

    /**
     * The reference `foldl-atom`'s own shape — `function` / `chain (context-space)` / `eval` around
     * the primitive — which is how every stdlib caller reaches it.
     */
    @Test
    fun `the reference foldl-atom definition folds`() {
        run(
            "FoldReference",
            """
            (: my-foldl (-> Expression Atom Variable Variable Atom %Undefined%))
            (= (my-foldl ~list ~init ~a ~b ~op)
              (function
                (chain (context-space) ~space
                  (eval (_minimal-foldl-atom ~list ~init ~a ~b ~op ~space)))))
            !(assertEqual (my-foldl (1 2 3 4) 0 ~x ~y (+ ~x ~y)) 10)
            """.trimIndent().v()
        )
    }
}
