package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end regression tests for the `get-type` builtin (d1_gadt / d3), compiling and running
 * each program. `:` type facts reach the running "self" space (FunctionRewriter) and the
 * [net.singularity.jetta.runtime.functions.TypeEngine] infers the MeTTa type; the argument is
 * ATOM (unreduced), so an ill-typed application like `(+ 5 "4")` is type-checked (→ empty set
 * `()`) rather than evaluated.
 *
 * Each `!(assertEqual …)` throws AssertionError from `__main` on a wrong answer, so a green
 * `invoke` IS the assertion. Cases whose programs use custom constructors as application heads
 * (no `=` rules) tolerate unresolved-symbol warnings via [runLenient].
 */
class GetTypeTest : GeneratorTestBase() {

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

    /** Grounded literals, arithmetic result type, and the bare-operator arrow. */
    @Test
    fun `literal, arithmetic and bare-operator types`() = run(
        "GetTypeLiteral",
        """
            !(assertEqual (get-type 5) Number)
            !(assertEqual (get-type (+ 5 7)) Number)
            !(assertEqual (get-type +) (-> Number Number Number))
        """.trimIndent()
    )

    /** Ill-typed expressions return the empty set `()`, not a value (and never crash). */
    @Test
    fun `ill-typed expressions are the empty set`() = run(
        "GetTypeIllTyped",
        """
            !(assertEqualToResult (get-type (+ 5 "4")) ())
            !(assertEqualToResult (get-type (+ -)) ())
        """.trimIndent()
    )

    /** A user `:`-declared type, and an arrow application with a `%Undefined%` wildcard param. */
    @Test
    fun `custom declared and arrow-applied types`() = runLenient(
        "GetTypeCustom",
        """
            (: Either Type)
            (: Left (-> %Undefined% Either))
            (: isLeft (-> Either Bool))
            (: Right (-> %Undefined% Either))
            !(assertEqual (get-type Either) Type)
            !(assertEqual (get-type (Left 5)) Either)
            !(assertEqual (get-type (isLeft (Right 5))) Bool)
            !(assertEqualToResult (get-type (isLeft 5)) ())
        """.trimIndent()
    )

    /** Parametric and recursively-parametric types with fresh type-variable instantiation. */
    @Test
    fun `parametric and recursive types`() = runLenient(
        "GetTypeParametric",
        $$"""
            (: EitherP (-> $t Type))
            (: LeftP (-> $t (EitherP $t)))
            (: List (-> $a Type))
            (: Nil (List $a))
            (: Cons (-> $a (List $a) (List $a)))
            !(assertEqual (get-type (LeftP 5)) (EitherP Number))
            !(assertEqual (get-type (Cons 5 (Cons 6 Nil))) (List Number))
            !(assertEqualToResult (get-type (Cons 5 (Cons "6" Nil))) ())
        """.trimIndent()
    )

    /** Form-2 pattern-`let` over a `get-type` result binds the type variable and returns it. */
    @Test
    fun `pattern-let extracts a type variable from a get-type result`() = runLenient(
        "GetTypeLet",
        $$"""
            (: List (-> $a Type))
            (: Nil (List $a))
            (: Cons (-> $a (List $a) (List $a)))
            !(assertEqual
               (let (List $t) (get-type (Cons 5 (Cons 6 Nil))) $t)
               Number)
        """.trimIndent()
    )
}
