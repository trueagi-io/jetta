package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A parameter a USER function declares literally as the meta-type `Atom` — hyperon's annotation for
 * "hand the argument over as the TERM". Until now only a builtin could say so: `inertAtomParams` was
 * reachable from `registerExternals` alone, so the reference stdlib's `filter-atom`, declared
 * `(-> Expression Variable Atom Expression)`, had its `(> $v 1)` reduced at the call site over a free
 * variable and died with a ClassCastException.
 *
 * It cannot be read off the descriptor, which is why it travels from the declaration: `asType()`
 * erases every type it does not know — `Number`, `Nat`, a user type — to `Atom` as well, and
 * reducing IS right for those.
 *
 * And it holds the argument only when that argument is a TEMPLATE, a term mentioning a variable
 * nothing in scope binds. Held unconditionally it breaks two things, both pinned below: hyperon keeps
 * reducing a function's RESULT, so passing a computable term unreduced merely defers the work there,
 * while JeTTa returns its result as it stands — the effect would simply never happen.
 */
class DeclaredAtomParamTest : GeneratorTestBase() {

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

    /** A TEMPLATE reaching a declared-`Atom` parameter arrives as the term. */
    @Test
    fun `a template reaching a declared Atom parameter is not reduced`() {
        run(
            "DeclaredAtomTemplate",
            """
            (: hold (-> Atom Atom))
            (= (hold _t) (kept _t))
            !(assertEqual (hold (foo _v 1)) (kept (foo _v 1)))
            """.trimIndent().d()
        )
    }

    /**
     * A COMPUTABLE term reaching the same parameter is computed. This is the case that decides the
     * rule: `e1_kb_write` writes to the space through `(: ift (-> Bool Atom %Undefined%))`, and if the
     * argument were held, the write would never happen — hyperon performs it while reducing `ift`'s
     * result, which JeTTa does not do.
     */
    @Test
    fun `a computable term reaching a declared Atom parameter is reduced`() {
        run(
            "DeclaredAtomComputable",
            """
            (: hold (-> Atom Atom))
            (= (hold _t) (kept _t))
            (: twice (-> Int Int))
            (= (twice _n) (* _n 2))
            !(assertEqual (hold (twice 21)) (kept 42))
            """.trimIndent().d()
        )
    }

    /** The effect version of the same rule, which is what e1_kb_write depends on. */
    @Test
    fun `an effect reaching a declared Atom parameter still happens`() {
        run(
            "DeclaredAtomEffect",
            """
            (: hold (-> Atom Atom))
            (= (hold _t) (kept _t))
            !(hold (add-atom &self (Written Here)))
            !(assertEqual (match &self (Written _w) _w) Here)
            """.trimIndent().d()
        )
    }

    /**
     * An ERASED type is not the meta-type: `asType()` turns `Number` into `Atom` too, and its
     * argument must still be reduced (f1_imports :65 — a body unwrapping a `Grounded` threw when the
     * un-reduced `(+ 1 2)` arrived instead).
     */
    @Test
    fun `an erased type does not suppress reduction`() {
        run(
            "DeclaredAtomErased",
            """
            (: r (-> Number Number))
            (= (r _x) (+ _x 100))
            !(assertEqual (r (+ 1 2)) 103)
            """.trimIndent().d()
        )
    }

    /**
     * The reference `filter-atom`, verbatim, end to end — the entry this was needed for. Its
     * predicate `(> $v 1)` reaches the declared-`Atom` parameter as a template, gets sealed,
     * substituted per element and only then run.
     */
    @Test
    fun `the reference filter-atom filters by a template predicate`() {
        run(
            "FilterAtomShape",
            """
            (: myfilter (-> Expression Variable Atom Expression))
            (= (myfilter _list _var _filter)
              (function (chain (decons-atom _list) _ht
                (unify (_head _tail) _ht
                  (chain (eval (sealed (_var) _filter)) _sealedfilter
                    (chain (eval (myfilter _tail _var _sealedfilter)) _tail-filtered
                      (chain (eval (atom-subst _head _var _sealedfilter)) _filter-expr
                        (chain _filter-expr _is-filtered
                          (eval (if _is-filtered
                            (chain (cons-atom _head _tail-filtered) _res (return _res))
                            (return _tail-filtered) ))))))
                  (return ()) ))))
            !(assertEqual (myfilter (1 2 3) _v (> _v 1)) (2 3))
            """.trimIndent().d()
        )
    }
}
