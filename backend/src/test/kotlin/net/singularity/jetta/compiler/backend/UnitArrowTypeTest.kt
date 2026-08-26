package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `(->)` — an arrow with NO components — is hyperon's UNIT type, which the reference stdlib declares
 * as the return of its side-effecting entries: `(: add-atoms (-> SpaceType Expression (->)))`, and
 * likewise `add-reducts` and `assert`.
 *
 * It is not a function type. Every `ArrowType` compiles to a `JettaFunction`, so such an entry
 * promised to return one while actually returning the unit expression `()` — and the multivalued
 * lift around it then cast: `ClassCastException: Expression cannot be cast to JettaFunction`, from
 * inside `simpleMap`.
 *
 * A green `invoke` IS the assertion. `~` stands for `$` (see `MinimalFoldTest`).
 */
class UnitArrowTypeTest : GeneratorTestBase() {

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

    /** The declared unit return carries the unit VALUE, `()`. */
    @Test
    fun `a function declared to return the unit arrow returns the unit atom`() {
        run(
            "UnitArrowScalar",
            """
            (: side (-> Atom (->)))
            (= (side ~x) ())
            !(assertEqual (side foo) ())
            """.trimIndent().v()
        )
    }

    /** Used in a `let`, which is where a genuinely VOID return would break instead. */
    @Test
    fun `a unit-returning call composes inside a let`() {
        run(
            "UnitArrowInLet",
            """
            (: side (-> Atom (->)))
            (= (side ~x) ())
            (= (go) (let ~ignored (side foo) done))
            !(assertEqual (go) done)
            """.trimIndent().v()
        )
    }

    /**
     * The reference `add-atoms` exactly as written — a unit return around a multivalued body, which
     * is the combination that produced the `JettaFunction` cast.
     */
    @Test
    fun `the reference add-atoms declaration writes to the space`() {
        run(
            "UnitArrowAddAtoms",
            """
            (: my-add-atoms (-> SpaceType Expression (->)))
            (= (my-add-atoms ~space ~tuple)
              (_minimal-foldl-atom ~tuple () ~a ~b (add-atom ~space ~b) &self))
            !(my-add-atoms &self ((p 1) (p 2)))
            !(assertEqualToResult (match &self (p ~n) ~n) (1 2))
            """.trimIndent().v()
        )
    }

    /** A unit-returning parameter TYPE erases the same way — a nested `(->)` is not a callback. */
    @Test
    fun `a nested unit arrow in a parameter position is not a function type`() {
        run(
            "UnitArrowParam",
            """
            (: takes-unit (-> (->) Atom))
            (= (takes-unit ~u) got)
            !(assertEqual (takes-unit ()) got)
            """.trimIndent().v()
        )
    }
}
