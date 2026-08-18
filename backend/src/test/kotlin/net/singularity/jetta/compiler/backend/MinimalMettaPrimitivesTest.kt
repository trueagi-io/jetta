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
}
