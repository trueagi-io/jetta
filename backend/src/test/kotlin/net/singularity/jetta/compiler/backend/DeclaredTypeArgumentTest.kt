package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A COMPUTED argument reaching a parameter whose declared type is not one the compiler knows.
 *
 * `FunctionRewriter.asType()` erases every unknown type — `Number`, `Nat`, a user type — to
 * `Atom`, and codegen read hyperon's meta-type rule "do not reduce the argument" off that same
 * erased descriptor. So a perfectly ordinary numeric function was handed an un-reduced
 * application and its body's `Grounded` unwrap threw. `Any` had a matching hole on the other
 * side: the call site boxes a computed primitive into the `Object` slot, while the callee cast
 * it straight to `Grounded`.
 *
 * Each program asserts via `!(assertEqual …)`; a wrong answer throws from `__main`, so a green
 * `invoke` IS the assertion.
 */
class DeclaredTypeArgumentTest : GeneratorTestBase() {

    private fun run(name: String, code: String) {
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach(::println)
                val classes = result.toMap().toClasses()
                JettaProgram.init(name)
                classes[name]!!.getMethod("__main").invoke(null)
            }
    }

    /**
     * `Number` is hyperon's grounded number type and is not in the compiler's type table, so it
     * erases to `Atom`. A LITERAL argument always worked; a computed one was frozen.
     */
    @Test
    fun `a computed argument reaches a Number-declared parameter reduced`() = run(
        "NumberParamComputedArg",
        $$"""
            (: r (-> Number Number))
            (= (r $x) (+ $x 100))
            !(assertEqual (r 3) 103)
            !(assertEqual (r (+ 1 2)) 103)
        """.trimIndent()
    )

    /** The same for a type that is unknown because the user invented it. */
    @Test
    fun `a computed argument reaches a user-type-declared parameter reduced`() = run(
        "UserTypeParamComputedArg",
        $$"""
            (: q (-> Nat Nat))
            (= (q $x) (* $x 2))
            !(assertEqual (q (+ 2 3)) 10)
        """.trimIndent()
    )

    /**
     * The `Any` side: the parameter slot is `Object`, so it may hold either a `Grounded` or the
     * bare box the call site produced from a computed primitive. Reading it as a primitive has to
     * accept both — the body uses it in arithmetic AND in a comparison, which are separate
     * coercion sites and were both casting straight to `Grounded`.
     */
    @Test
    fun `an Any-declared parameter accepts a bare box in arithmetic and in a comparison`() = run(
        "AnyParamBareBox",
        $$"""
            (: r (-> Any Any))
            (= (r $x) (if (< $x 0) (- 0 $x) (+ $x 100)))
            !(assertEqual (r (+ 1 2)) 103)
            !(assertEqual (r (- 0 5)) 5)
        """.trimIndent()
    )

    /**
     * The shape f1_moduleA/f1_moduleC have, in one file: a `Number`-declared callee reached
     * through a `Number`-declared caller, with the inner call's argument computed.
     */
    @Test
    fun `a Number-declared callee called from a Number-declared caller`() = run(
        "NumberChain",
        $$"""
            (: g (-> Number Number))
            (= (g $x) (+ $x 100))
            (: f (-> Number Number))
            (= (f $x) (if (< $x 0) (- 0 $x) (g (+ 1 $x))))
            !(assertEqual (f 2) 103)
            !(assertEqual (f -5) 5)
        """.trimIndent()
    )
}
