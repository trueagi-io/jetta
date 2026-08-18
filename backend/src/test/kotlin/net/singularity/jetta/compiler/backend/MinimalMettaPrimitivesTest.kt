package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The minimal-MeTTa primitives the reference `stdlib.metta` is written in, beyond `unify`:
 * `if-equal`, `get-metatype`, and `eval` over an argument the call site already evaluated.
 *
 * Together with `unify` these are what turn hyperon's own stdlib from a file that merely compiles
 * into one whose entries answer: `if-error` and `match-types` both went from crashing to correct on
 * the strength of exactly these three.
 *
 * Each program asserts with `!(assertEqual …)`, which throws out of `__main` on a wrong answer, so a
 * green `invoke` IS the assertion.
 */
class MinimalMettaPrimitivesTest : GeneratorTestBase() {

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

    /** `if-equal` picks a branch by STRUCTURAL equality — it matches nothing and binds nothing. */
    @Test
    fun `if-equal picks a branch by structural equality`() {
        run(
            "IfEqualBranch",
            """
            (= (cmp _a _b) (if-equal _a _b same different))
            !(assertEqual (cmp (A B) (A B)) same)
            !(assertEqual (cmp (A B) (A C)) different)
            !(assertEqual (cmp X X) same)
            """.trimIndent().d()
        )
    }

    /**
     * Both arguments are INERT, as in hyperon, where `if-equal` takes meta-type `Atom`: the
     * comparison is between the terms as written, so `(+ 1 2)` is not `3`.
     */
    @Test
    fun `if-equal compares its arguments unreduced`() {
        run(
            "IfEqualInert",
            """
            (= (probe) (if-equal (+ 1 2) 3 reduced unreduced))
            !(assertEqual (probe) unreduced)
            """.trimIndent().d()
        )
    }

    /**
     * Only the taken branch runs. The reference stdlib puts `(Error …)` in an else-branch and
     * relies on it, and the observable version of that here is a side effect: the untaken branch
     * writes to the space, and the space must not show it.
     */
    @Test
    fun `if-equal evaluates only the branch it takes`() {
        run(
            "IfEqualLazy",
            """
            (= (probe) (if-equal X X taken (add-atom &self (Untaken))))
            !(assertEqual (probe) taken)
            !(assertEqualToResult (match &self (Untaken) found) ())
            """.trimIndent().d()
        )
    }

    /** `get-metatype` answers which of MeTTa's four kinds of atom it was given. */
    @Test
    fun `get-metatype names the kind of atom`() {
        run(
            "GetMetatype",
            """
            !(assertEqual (get-metatype Foo) Symbol)
            !(assertEqual (get-metatype (Foo Bar)) Expression)
            !(assertEqual (get-metatype 42) Grounded)
            !(assertEqual (get-metatype "s") Grounded)
            !(assertEqual (get-metatype _x) Variable)
            """.trimIndent().d()
        )
    }

    /** Its argument is inert too: the metatype of `(+ 1 2)` is `Expression`, not of its value. */
    @Test
    fun `get-metatype does not reduce its argument`() {
        run(
            "GetMetatypeInert",
            """
            !(assertEqual (get-metatype (+ 1 2)) Expression)
            """.trimIndent().d()
        )
    }

    /**
     * `eval` over an argument the call site already reduced is the identity — JeTTa reduces eagerly
     * wherever it reduces, so there is no step left to take. This is the reference stdlib's shape
     * everywhere (`(eval (get-metatype $atom))`), and each such call used to reach the JIT as a
     * result `List` against a parameter typed `Atom`, storing a List into an `Atom[]`.
     */
    @Test
    fun `eval of an already evaluated argument is the identity`() {
        run(
            "EvalIdentity",
            """
            (: twice (-> Int Int))
            (= (twice _n) (* _n 2))
            !(assertEqual (eval (twice 21)) 42)
            !(assertEqual (eval (get-metatype (Foo Bar))) Expression)
            """.trimIndent().d()
        )
    }

    /** The case `eval` exists for is untouched: a program handed over as DATA is compiled. */
    @Test
    fun `eval still compiles a quoted program`() {
        run(
            "EvalQuoted",
            """
            !(assertEqual (eval (quote (+ 1 2))) 3)
            """.trimIndent().d()
        )
    }

    /**
     * `eval` hands back anything that is not a runnable program. A value has nothing to compile, an
     * expression headed by a number is a data TUPLE, and one carrying a variable is a TEMPLATE — all
     * three arise in the reference `map-atom`, whose steps `eval` things earlier steps reduced.
     */
    @Test
    fun `eval hands back a value, a data tuple and a template`() {
        run(
            "EvalNonProgram",
            """
            !(assertEqual (eval 4) 4)
            !(assertEqual (eval (quote (3 4))) (3 4))
            !(assertEqual (eval (quote (+ _v 1))) (+ _v 1))
            """.trimIndent().d()
        )
    }

    /**
     * `sealed` renames every variable EXCEPT the ones listed — note the direction, which is how
     * hyperon documents it — and `atom-subst` substitutes for the variable a value NAMES.
     *
     * `atom-subst` is native rather than taken from the reference stdlib, whose definition binds
     * *the variable that `$var`'s value names* through `chain`; our `chain` lowers onto `let`, which
     * binds the syntactic variable instead. As a builtin it shadows that rule.
     *
     * Only the KEEP direction is asserted here — a renamed variable gets a globally fresh name, so
     * there is nothing stable to compare it against. That the renaming happens at all is what the
     * map-atom test below depends on.
     */
    @Test
    fun `sealed keeps the listed variables and atom-subst substitutes for one`() {
        run(
            "SealedSubst",
            """
            (: keep (-> Variable Atom Atom))
            (= (keep _var _t) (sealed (_var) _t))
            (: subst (-> Variable Atom Atom))
            (= (subst _var _t) (atom-subst 7 _var _t))
            !(assertEqual (keep _v (tpl _v 1)) (tpl _v 1))
            !(assertEqual (subst _v (tpl _v 1)) (tpl 7 1))
            """.trimIndent().d()
        )
    }

    /**
     * `chain` over a bare VARIABLE runs the program that variable holds. It is the one shape where
     * lowering onto `let` is not enough — a `let` would bind the template itself, unevaluated — and
     * the reference `map-atom` depends on it: it builds a template with `atom-subst` and then steps
     * it. Routed through `eval`, i.e. through runtime compilation.
     */
    @Test
    fun `chain over a variable runs the atom it holds`() {
        run(
            "ChainOverVariable",
            """
            (: step (-> Atom Atom))
            (= (step _prog) (chain _prog _value (result _value)))
            !(assertEqual (step (+ 20 22)) (result 42))
            """.trimIndent().d()
        )
    }

    /**
     * The reference `map-atom`, verbatim, end to end. It stands on every primitive in this file at
     * once — `unify` to destructure, `sealed` + `atom-subst` to build the per-element template,
     * `chain` over a variable to run it, `eval` to know when not to — and its five nested `let`s are
     * what forced the lift's bag test to become recursive.
     */
    @Test
    fun `the reference map-atom maps a template over a list`() {
        run(
            "MapAtomShape",
            """
            (: mymap (-> Expression Variable Atom Expression))
            (= (mymap _list _var _map)
              (function (chain (decons-atom _list) _ht
                (unify (_head _tail) _ht
                  (chain (eval (sealed (_var) _map)) _sealedmap
                    (chain (eval (mymap _tail _var _sealedmap)) _tail-mapped
                      (chain (eval (atom-subst _head _var _sealedmap)) _map-expr
                        (chain _map-expr _head-mapped
                          (chain (cons-atom _head-mapped _tail-mapped) _res (return _res)) ))))
                  (return ()) ))))
            !(assertEqual (mymap (1 2 3) _v (+ _v 1)) (2 3 4))
            """.trimIndent().d()
        )
    }
}
