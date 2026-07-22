package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression tests for `get-doc` / `help!` (g1_docs). Documentation (`@doc`) and type (`:`)
 * forms now reach the running "self" space as facts (FunctionRewriter), and `get-doc` queries
 * them to assemble the `@doc-formal` structure the reference suite expects; `help!` prints it.
 *
 * Each program asserts via `!(assertEqual …)`; a wrong answer throws AssertionError from
 * `__main`, so a green `invoke` IS the assertion. Cases 1–4 use only bare-symbol arguments
 * (no unresolved-symbol warnings, so [run] can require an empty collector); the application
 * case queries an undefined-function application and is checked with [runLenient].
 */
class DocTest : GeneratorTestBase() {

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
     * A documented function with a declared arrow type: `@kind function` (it has `@params`),
     * the `@type` slot keeps the whole `(-> …)`, and each parameter/return type is filled from
     * the arrow. Descriptions are reused verbatim from the `@doc` body.
     */
    @Test
    fun `get-doc of a documented function with an arrow type`() = run(
        "DocFunction",
        """
            (@doc some-func
                 (@desc "Test function")
                 (@params (
                          (@param "First argument")
                          (@param "Second argument")))
                 (@return "Return value"))
            (: Arg1Type Type)
            (: Arg2Type Type)
            (: ReturnType Type)
            (: some-func (-> Arg1Type Arg2Type ReturnType))
            !(assertEqual
               (get-doc some-func)
               (@doc-formal (@item some-func) (@kind function)
                           (@type (-> Arg1Type Arg2Type ReturnType))
                           (@desc "Test function")
                           (@params (
                                    (@param (@type Arg1Type) (@desc "First argument"))
                                    (@param (@type Arg2Type) (@desc "Second argument"))))
                           (@return (@type ReturnType) (@desc "Return value"))))
        """.trimIndent()
    )

    /** A documented atom with a non-arrow declared type: `@kind atom`, single `@type` slot. */
    @Test
    fun `get-doc of a documented atom with a plain type`() = run(
        "DocAtom",
        """
            (@doc SomeSymbol (@desc "Test symbol atom having specific type"))
            (: SomeSymbol SomeType)
            !(assertEqual
               (get-doc SomeSymbol)
               (@doc-formal (@item SomeSymbol) (@kind atom) (@type SomeType)
                           (@desc "Test symbol atom having specific type")))
        """.trimIndent()
    )

    /**
     * A documented function with NO declared type: still `@kind function` (has `@params`), but
     * every type slot — top-level, each param, and the return — is the symbol `%Undefined%`.
     */
    @Test
    fun `get-doc of a documented function without a declared type uses percent-Undefined`() = run(
        "DocFunctionUndefined",
        """
            (@doc some-gnd-atom
                 (@desc "Test function")
                 (@params (
                          (@param "First argument")
                          (@param "Second argument")))
                 (@return "Return value"))
            !(assertEqual
               (get-doc some-gnd-atom)
               (@doc-formal (@item some-gnd-atom) (@kind function)
                           (@type %Undefined%)
                           (@desc "Test function")
                           (@params (
                                    (@param (@type %Undefined%) (@desc "First argument"))
                                    (@param (@type %Undefined%) (@desc "Second argument"))))
                           (@return (@type %Undefined%) (@desc "Return value"))))
        """.trimIndent()
    )

    /** An undocumented symbol → `Empty`. */
    @Test
    fun `get-doc of an undocumented symbol is Empty`() = run(
        "DocUndocumented",
        """
            !(assertEqual (get-doc NoSuchAtom) Empty)
        """.trimIndent()
    )

    /**
     * Querying an application (not a documented symbol) → `Empty`. The ATOM meta-type keeps the
     * argument an inert Expression, which never structurally equals a documented `@doc` subject
     * Symbol. `some-func` as an application head is undefined, hence the tolerated warning.
     */
    @Test
    fun `get-doc of an application is Empty`() = runLenient(
        "DocApplication",
        """
            (@doc some-func (@desc "Test function") (@params ((@param "a"))) (@return "r"))
            (: some-func (-> Arg1Type ReturnType))
            !(assertEqual (get-doc (some-func arg1 arg2)) Empty)
        """.trimIndent()
    )

    /** `help!` runs without crashing on both a documented function and an undocumented atom. */
    @Test
    fun `help! prints documentation and tolerates undocumented atoms`() = run(
        "DocHelp",
        """
            (@doc some-func
                 (@desc "Test function")
                 (@params ((@param "First argument")))
                 (@return "Return value"))
            (: some-func (-> Arg1Type ReturnType))
            !(help! some-func)
            !(help! NoSuchAtom)
        """.trimIndent()
    )
}
