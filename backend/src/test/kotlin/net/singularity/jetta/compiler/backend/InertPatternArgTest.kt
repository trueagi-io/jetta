package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A pattern argument must reach the matcher as DATA — `JvmMethod.inertAtomParams`, the same
 * mechanism `get-type` uses.
 *
 * `letMatch` did not declare it, so its pattern took the ordinary `generateAtom` path, where an
 * expression whose HEAD is a variable is compiled as a var-head DISPATCH call (`JettaCallSite`)
 * rather than an inert `Expression`. The "pattern" was then dispatched at runtime and the match ran
 * against its result, so every variable-headed pattern silently produced an EMPTY result bag — no
 * error, no diagnostic, just nothing. A symbol-headed pattern escaped because an unresolved Symbol
 * head already lands on the data-constructor path, which is why this went unnoticed: `(P $a $b)`
 * worked and `($a $b)` did not.
 *
 * It matters because `($head $tail)` is the dominant pattern shape in the reference `stdlib.metta`
 * (`car-atom`, `cdr-atom`, `filter-atom`, `map-atom`, `let*` all destructure that way).
 *
 * `letMatch` is a registered system function, so the pattern-`let` runtime can be exercised
 * directly here; `LetRewriter` only emits it for single-variable patterns.
 */
class InertPatternArgTest : GeneratorTestBase() {

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

    /** A VARIABLE-headed pattern — the shape that used to dispatch instead of matching. */
    @Test
    fun `a variable headed pattern matches as data`() {
        run(
            "InertPatternVarHead",
            """
            (= (second _x) (letMatch (_a _b) _x (\ (_a _b) _b)))
            !(assertEqual (second (1 2)) 2)
            """.trimIndent().d()
        )
    }

    /** The symbol-headed pattern that always worked, kept so the fix is not a swap. */
    @Test
    fun `a symbol headed pattern still matches`() {
        run(
            "InertPatternSymbolHead",
            """
            (= (second _x) (letMatch (P _a _b) _x (\ (_a _b) _b)))
            !(assertEqual (second (P 1 2)) 2)
            """.trimIndent().d()
        )
    }

    /** A pattern with no variables at all: the branch lambda takes no arguments. */
    @Test
    fun `a pattern with no variables applies a zero argument body`() {
        run(
            "InertPatternNoVars",
            """
            (= (konst _x) (letMatch (P) _x (\ () 7)))
            !(assertEqual (konst (P)) 7)
            """.trimIndent().d()
        )
    }
}
