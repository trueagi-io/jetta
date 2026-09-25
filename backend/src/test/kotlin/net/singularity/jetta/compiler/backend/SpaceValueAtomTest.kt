package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A SPACE reference crossing a boundary that requires an `Atom`.
 *
 * In a value position a `&`-name is lowered to a bare String — the convention every space-taking
 * BUILTIN reads, since their space parameter is typed `Object`. Two boundaries do not tolerate it:
 *
 *  * a parameter a callee declares `Atom`. `Atom` is an INTERFACE, so the verifier does not reject
 *    the String; it travels in and explodes wherever an Atom is genuinely needed.
 *  * a quoted TEMPLATE, whose `Atom[]` rejects it at the `AASTORE`.
 *
 * Both were reached by the reference `add-atoms` / `add-reducts`, which pass their space parameter
 * into the template `(add-atom $space $b)` — `ArrayStoreException: java.lang.String`.
 *
 * A green `invoke` IS the assertion (a wrong answer throws out of `__main`); every case here was
 * checked to FAIL against a wrong expectation. `~` stands for `$` (see `MinimalFoldTest`).
 */
class SpaceValueAtomTest : GeneratorTestBase() {

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

    /**
     * The `add-atoms` shape with a DECLARED space parameter: `SpaceType` erases to `Atom`, so the
     * call site must hand over the Symbol form rather than the bare String.
     */
    @Test
    fun `a space reaches a declared Atom parameter and still writes`() {
        run(
            "SpaceDeclaredParam",
            """
            (: my-add-atoms (-> SpaceType Expression %Undefined%))
            (= (my-add-atoms ~space ~tuple)
              (_minimal-foldl-atom ~tuple () ~a ~b (add-atom ~space ~b) &self))
            !(my-add-atoms &self ((likes Sam Ann)))
            !(assertEqual (match &self (likes Sam ~who) ~who) Ann)
            """.trimIndent().v()
        )
    }

    /**
     * The same write through an UNDECLARED parameter, which `add-atom`'s `Object` space slot infers
     * as `Any`: the String survives into the function and has to be coerced where it is stored into
     * the template (`JettaProgram.asQuotedAtom`).
     */
    @Test
    fun `a space reaches an inferred Any parameter and still writes`() {
        run(
            "SpaceAnyParam",
            """
            (= (my-op ~space) (_minimal-foldl-atom (1 2) () ~a ~b (add-atom ~space ~b) &self))
            !(my-op &self)
            !(assertEqualToResult (match &self (1) found) (found))
            """.trimIndent().v()
        )
    }

    /** A named sub-space is the same story without the `&self` resolution step. */
    @Test
    fun `a named sub-space reaches a declared Atom parameter`() {
        run(
            "SpaceNamedParam",
            """
            (: my-add-atoms (-> SpaceType Expression %Undefined%))
            (= (my-add-atoms ~space ~tuple)
              (_minimal-foldl-atom ~tuple () ~a ~b (add-atom ~space ~b) &self))
            !(my-add-atoms &kb ((fact A)))
            !(assertEqual (match &kb (fact ~x) ~x) A)
            !(assertEqualToResult (match &self (fact ~x) ~x) ())
            """.trimIndent().v()
        )
    }
}
