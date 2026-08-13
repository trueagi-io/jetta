package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `let` inside a `let`. Each `let` is an immediately applied lambda, and the inner one collapses
 * to `(\ ($b) $b)` — a lambda whose body is a bare Variable. `Lambda.capturedVariables` used to
 * walk that shape without adding the inner lambda's own parameters to the bound set, so `$b` was
 * reported as a capture of the OUTER lambda: a phantom leading parameter on the synthetic method,
 * and at the `invokedynamic` creation site an IR `Variable` object pushed where the descriptor
 * promised the value ("Type 'Variable' is not assignable to integer", at class load).
 *
 * The reference `stdlib.metta` is written in nested `chain`s, which are nested `let`s.
 */
class NestedLetCaptureTest : GeneratorTestBase() {
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

    /** Declared `Int` throughout — the plainest form of the shape. */
    @Test
    fun `a let nested in a let over primitives`() = run(
        "NestedLetInt",
        """
            (: g (-> Int Int))
            (= (g ${'$'}x) (let ${'$'}a (+ ${'$'}x 1) (let ${'$'}b (* ${'$'}a 2) ${'$'}b)))
            !(assertEqual (g 3) 8)
        """.trimIndent()
    )

    /** `Number` erases to `Atom`, so the parameter arrives as a reference — same shape, other slot type. */
    @Test
    fun `a let nested in a let under an erased parameter type`() = run(
        "NestedLetErased",
        """
            (: g (-> Number Number))
            (= (g ${'$'}x) (let ${'$'}a (+ ${'$'}x 1) (let ${'$'}b (* ${'$'}a 2) ${'$'}b)))
            !(assertEqual (g 3) 8)
        """.trimIndent()
    )

    /** Three deep, and the innermost body referring to a binding from the outermost. */
    @Test
    fun `three nested lets referring across levels`() = run(
        "NestedLetThree",
        """
            (: g (-> Int Int))
            (= (g ${'$'}x)
              (let ${'$'}a (+ ${'$'}x 1)
                (let ${'$'}b (* ${'$'}a 2)
                  (let ${'$'}c (- ${'$'}b ${'$'}a) (+ ${'$'}c ${'$'}x)))))
            !(assertEqual (g 3) 7)
        """.trimIndent()
    )
}
