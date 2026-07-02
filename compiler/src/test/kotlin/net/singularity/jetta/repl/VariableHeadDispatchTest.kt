package net.singularity.jetta.repl

import net.singularity.jetta.compiler.frontend.ir.Grounded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Higher-order MeTTa where the applied head is a plain (untyped) variable — `($f $x)` in
 * a body like `(= (apply $f $x) ($f $x))`. Unlike [ReplTest.passFunction] (whose `$f` has
 * a static `(-> …)` arrow type and lowers to a direct `JettaFunction.apply`), an untyped
 * `$f` is `Atom`-typed, so codegen defers to the runtime dispatcher
 * ([net.singularity.jetta.runtime.functions.JettaCallSite]). When a JIT env is live
 * (same-JVM / REPL) the dispatcher EVALUATES the application by JIT-compiling+running it,
 * so `$f` bound to a function-naming Symbol yields a value (grounded ops evaluate, user
 * functions link against their compiled classes).
 *
 * Also exercises the call-site coercion that had to land first: an Int literal passed to
 * the `Atom`-typed parameter of `apply` is wrapped in a `Grounded` (a bare `Integer` is
 * not an `Atom` subtype), which is what lets the argument reach `apply` at all.
 */
class VariableHeadDispatchTest {
    private fun String.d() = replace('_', '$')

    private val defs = """
        (: inc (-> Int Int))
        (= (inc _n) (+ _n 1))
        (= (dbl _n) (* _n 2))
        (= (apply _f _x) (_f _x))
        (= (twice _f _x) (_f (_f _x)))
    """.trimIndent().d()

    private fun repl(): Repl = ReplImpl().also { assertTrue(it.eval(defs).isSuccess) }

    // The dispatcher evaluates through JIT-eval, whose results are the `List<Atom>`
    // non-determinism bag — a scalar comes back as a `Grounded` Atom, not a raw Int
    // (unlike a direct compiled call). Unwrap it for comparison.
    private fun EvalResult.intValue(): Any? = (result as? Grounded<*>)?.value ?: result

    @Test
    fun `variable head applies a named compiled function`() {
        val r = repl()
        r.eval("!(apply inc 5)").let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals(6, it.intValue())
        }
        r.eval("!(apply dbl 5)").let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals(10, it.intValue())
        }
    }

    @Test
    fun `nested variable head application`() {
        repl().eval("!(twice inc 10)").let {
            assertTrue(it.isSuccess, it.messages.toString())
            assertEquals(12, it.intValue())
        }
    }
}
