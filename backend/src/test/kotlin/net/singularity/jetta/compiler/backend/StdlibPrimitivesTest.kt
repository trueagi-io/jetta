package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Grounded stdlib primitives that are not MeTTa-level definitions in `stdlib.metta`:
 * `id`, `=alpha`, `get-type-space`. Expectations read off hyperon 0.2.10.
 */
class StdlibPrimitivesTest : GeneratorTestBase() {

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

    @Test
    fun `id returns its argument`() = run(
        "PrimId",
        """
            !(assertEqual (id 5) 5)
            !(assertEqual (id (a b)) (a b))
            !(assertEqual (id (+ 1 2)) 3)
        """.trimIndent()
    )

    /**
     * `=alpha` is one token. It used to lex as the rule-head `=` followed by `alpha`, so
     * `(=alpha (Father $X) (Father $Y))` parsed as the four-element `(= alpha … …)` — the
     * grammar now lets `=` START a name when a name character follows it immediately.
     */
    @Test
    fun `=alpha compares up to a renaming of variables`() = run(
        "PrimAlpha",
        $$"""
            !(assertEqual (=alpha (Father $X) (Father $Y)) True)
            !(assertEqual (=alpha (Father $X) (Son $X)) False)
            !(assertEqual (=alpha (a b) (a b)) True)
            !(assertEqual (=alpha (a b) (a c)) False)
        """.trimIndent()
    )

    /**
     * The renaming has to be a BIJECTION. Read one way only, `$a`->`$c` and `$b`->`$c` are
     * both consistent, which would wrongly make `(f $a $b)` alpha-equivalent to `(f $c $c)`.
     */
    @Test
    fun `=alpha requires a bijection, not just a consistent map`() = run(
        "PrimAlphaBijection",
        $$"""
            !(assertEqual (=alpha (f $a $b) (f $c $c)) False)
            !(assertEqual (=alpha (f $a $a) (f $c $d)) False)
            !(assertEqual (=alpha (f $a $b) (f $c $d)) True)
        """.trimIndent()
    )

    /** A bare `=` still heads a rule — the grammar change must not swallow it. */
    @Test
    fun `a rule head is still a bare equals`() = run(
        "PrimEqualsStillRule",
        $$"""
            (= (twice $x) (+ $x $x))
            !(assertEqual (twice 4) 8)
        """.trimIndent()
    )

    /** `get-type-space` is `get-type` against a named space. */
    @Test
    fun `get-type-space reads the named space`() = run(
        "PrimGetTypeSpace",
        """
            (: a A)
            !(assertEqual (get-type-space &self a) A)
        """.trimIndent()
    )
}
