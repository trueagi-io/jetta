package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * minimal MeTTa's `(unify $a $b $then $else)`, lowered onto the `unifyMatch` runtime helper by
 * `FunctionRewriter.lowerUnify`. These are the shapes the reference `stdlib.metta` actually writes —
 * `car-atom`, `cdr-atom`, `let*`, `get-doc` — because `unify` is the primitive the largest part of
 * that file stands on (36 of its entries, transitively).
 *
 * Each program asserts with `!(assertEqual …)`, which throws from `__main` on a wrong answer, so a
 * green `invoke` IS the assertion. Every case here was first checked to FAIL when the expectation is
 * wrong — an empty result bag silently satisfies nothing, and that is exactly how the
 * variable-headed pattern bug below hid.
 */
class UnifyLoweringTest : GeneratorTestBase() {

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

    private fun String.d() = replace('_', '$')

    /**
     * The reference `car-atom`: a VARIABLE-HEADED pattern `($head $tail)` whose variables are
     * fresh, unified against the `decons-atom` pair, with the head returned from the then-branch.
     */
    @Test
    fun `the reference car-atom shape binds a fresh pattern variable`() {
        run(
            "UnifyCar",
            """
            (= (my-car _atom)
              (chain (decons-atom _atom) _ht
                (unify (_head _tail) _ht _head (Error (my-car _atom) "empty"))))
            !(assertEqual (my-car (1 2 3)) 1)
            """.trimIndent().d()
        )
    }

    /** The same shape returning the OTHER pattern variable — the reference `cdr-atom`. */
    @Test
    fun `the reference cdr-atom shape returns the tail`() {
        run(
            "UnifyCdr",
            """
            (= (my-cdr _atom)
              (chain (decons-atom _atom) _ht
                (unify (_head _tail) _ht _tail (Error (my-cdr _atom) "empty"))))
            !(assertEqual (my-cdr (1 2 3)) (2 3))
            """.trimIndent().d()
        )
    }

    /**
     * A failed unification takes the else-branch — the reason `unify` cannot be rewritten away the
     * way `chain` / `function` / `return` are: there is nowhere for the failure to go.
     */
    @Test
    fun `a failed unification evaluates the else branch`() {
        run(
            "UnifyElse",
            """
            (= (my-car _atom)
              (chain (decons-atom _atom) _ht
                (unify (_head _tail) _ht _head empty-input)))
            !(assertEqual (my-car ()) empty-input)
            """.trimIndent().d()
        )
    }

    /**
     * Both atoms bare variables / constants: no variable is bound, `unify` degenerates to a
     * structural comparison and picks a branch. This is how the reference stdlib tests metatypes
     * (`(unify $mtype Expression … …)`) and sizes (`(unify $size 0 … …)`).
     */
    @Test
    fun `a unification of two values degenerates to a comparison`() {
        run(
            "UnifyCompare",
            """
            (= (cmp _x) (unify _x Expression yes no))
            !(assertEqual (cmp Expression) yes)
            !(assertEqual (cmp Symbol) no)
            """.trimIndent().d()
        )
    }

    /**
     * The reference `let*` shape — a `unify` NESTED in another's then-branch. `$head` is bound by
     * the outer `unify`, so for the inner one it is an ordinary in-scope VALUE and must NOT become
     * a parameter of the inner then-branch; `$p`/`$v` are the inner one's own fresh variables.
     * This is why the lowering threads scope top-down instead of over-approximating: a `unify`'s
     * pattern variables are binders for its own branch and values for anything nested inside it.
     */
    @Test
    fun `a nested unify sees the enclosing binding as a value`() {
        run(
            "UnifyNested",
            """
            (= (my-lets _pairs _template)
              (chain (decons-atom _pairs) _ht
                (unify (_head _tail) _ht
                  (unify (_p _v) _head (ok _p _v) (Error bad))
                  _template)))
            !(assertEqual (my-lets ((a 5)) done) (ok a 5))
            """.trimIndent().d()
        )
    }

    /**
     * Four pattern variables — the reference `get-doc`'s `(@doc $name $desc (@params $params) $ret)`.
     * The branch lambda takes one argument per pattern variable, a count no system-function
     * declaration can state: typing its parameters straight off the declared arrow threw
     * ArrayIndexOutOfBounds and killed the whole compile (`Context.resolveAtom`, Lambda arm).
     */
    @Test
    fun `a four variable pattern binds every variable`() {
        run(
            "UnifyFourVars",
            """
            (= (doc4 _x) (unify _x (@doc _n _d (@params _p) _r) (found _n _r) none))
            !(assertEqual (doc4 (@doc foo "desc" (@params ()) Bar)) (found foo Bar))
            """.trimIndent().d()
        )
    }

    /**
     * A pattern that arrives as a PARAMETER's value, containing variables of its own — hyperon's
     * `switch-internal` / `if-decons-expr` shape. Nothing is bound for the branch here (both sides
     * are in-scope names), so the then-branch takes no arguments and codegen's own bound-vs-free
     * split inside the quoted atom does the work.
     */
    @Test
    fun `a pattern supplied as an argument value still unifies`() {
        run(
            "UnifyDynamicPattern",
            """
            (= (probe _pat _val) (unify _val _pat matched nomatch))
            !(assertEqual (probe (A _x) (A 1)) matched)
            !(assertEqual (probe (A _x) (B 1)) nomatch)
            """.trimIndent().d()
        )
    }
}
