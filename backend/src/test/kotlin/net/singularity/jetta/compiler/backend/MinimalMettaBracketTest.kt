package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * minimal MeTTa's evaluation bracket, which the reference `stdlib.metta` is written in.
 * `(function X)` reduces X one step at a time until it becomes `(return $v)` and yields `$v`;
 * JeTTa evaluates to a value wherever it evaluates at all, so both halves are the identity and
 * the `chain`s between them are the `let`s they already rewrite to.
 *
 * The last case is the bracket over a bag, which needs the marking `MultivaluedInLetTest` covers
 * and the capture fix `NestedLetCaptureTest` covers — the three landed together.
 */
class MinimalMettaBracketTest : GeneratorTestBase() {
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

    /** The bracket around a single `chain` is exactly a `let`. */
    @Test
    fun `function around a chain returning its binding`() = run(
        "BracketChain",
        """
            (: f (-> Number Number))
            (= (f ${'$'}x) (function (chain (+ ${'$'}x 1) ${'$'}v (return ${'$'}v))))
            !(assertEqual (f 1) 2)
        """.trimIndent()
    )

    /** Nested chains with the `return` in the innermost one — the `switch-minimal` shape. */
    @Test
    fun `function around nested chains`() = run(
        "BracketNested",
        """
            (: g (-> Number Number))
            (= (g ${'$'}x)
              (function
                (chain (+ ${'$'}x 1) ${'$'}a
                  (chain (* ${'$'}a 2) ${'$'}b
                    (return ${'$'}b)))))
            !(assertEqual (g 3) 8)
        """.trimIndent()
    )

    /** `return` reached through a branch, and a bare `return` as the whole body. */
    @Test
    fun `return in a branch and as a whole body`() = run(
        "BracketBranch",
        """
            (: h (-> Number Number))
            (= (h ${'$'}x) (function (if (> ${'$'}x 0) (return ${'$'}x) (return 0))))
            (: k (-> Number Number))
            (= (k ${'$'}x) (return ${'$'}x))
            !(assertEqual (h 5) 5)
            !(assertEqual (h -5) 0)
            !(assertEqual (k 7) 7)
        """.trimIndent()
    )

    /** The same through the `chain` spelling, i.e. exactly how the reference stdlib writes it. */
    @Test
    fun `a multivalued call inside a chain under the bracket`() = run(
        "MarkThroughChain",
        """
            (: pick2 (-> Expression Expression))
            (= (pick2 ${'$'}cases)
              (function (chain (superpose (${'$'}cases ${'$'}cases)) ${'$'}x (return ${'$'}x))))
            !(assertEqualToResult (pick2 (A B)) ((A B) (A B)))
        """.trimIndent()
    )
}
