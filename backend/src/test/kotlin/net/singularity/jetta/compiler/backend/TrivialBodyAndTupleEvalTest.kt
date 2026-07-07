package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two applicative-order fixes that let a call to a user function actually reduce and run
 * its argument's side effects — the pieces the `hide` idiom (`(= (hide $x) ())`, used to
 * evaluate an expression for effect and discard the result) depends on:
 *
 *  - Gap #1: a function whose body type cannot be inferred (a bare-variable identity body,
 *    `(= (I $x) $x)`) was never registered in `resolvedFunctions`, so a call `(I 5)` stayed
 *    inert data instead of an INVOKESTATIC. `Context.registerUntypedFunctions` now defaults
 *    such a function to an Atom signature and registers it before the final call-site pass.
 *
 *  - Gap #2: a tuple argument headed by a call, `((add-atom …) (add-atom …))`, did not
 *    reduce its elements in a value position. Two fixes cooperate: `FunctionGenerator`
 *    quotes such an argument with `evalCalls = true` (reducible scalar elements evaluate,
 *    genuine data stays inert), and `Context.resolveAtom`'s data-constructor descent now
 *    scans ALL elements including the head (element 0 was previously dropped, so only the
 *    last element of the tuple ran).
 *
 * Each test compiles a whole program and invokes `__main`; a failed `!(assertEqual …)`
 * throws `AssertionError`, so a clean run is the assertion.
 */
class TrivialBodyAndTupleEvalTest : GeneratorTestBase() {

    @Test
    fun `identity-body function call reduces to an INVOKESTATIC`() {
        compile(
            "TrivIdentity.metta",
            $$"""
                (= (ident $x) $x)
                !(assertEqual (ident 5) 5)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), mc.list().toString())
            val cls = result.toMap().toClasses()["TrivIdentity"]!!
            JettaProgram.init("TrivIdentity")
            cls.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `a tuple argument headed by a call runs every element's side effect`() {
        compile(
            "TupleArgEval.metta",
            $$"""
                (= (seetuple $x) 42)
                !(seetuple ((add-atom &self (m 1)) (add-atom &self (m 2))))
                !(assertEqual (msort (collapse (match &self (m $y) $y))) (1 2))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), mc.list().toString())
            val cls = result.toMap().toClasses()["TupleArgEval"]!!
            JettaProgram.init("TupleArgEval")
            cls.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `the hide idiom evaluates its argument for effect and discards the result`() {
        compile(
            "HideIdiom.metta",
            $$"""
                (= (hide $x) ())
                !(hide ((add-atom &self (h 1)) (add-atom &self (h 2))))
                !(assertEqual (msort (collapse (match &self (h $y) $y))) (1 2))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, mc) ->
            assertTrue(mc.list().isEmpty(), mc.list().toString())
            val cls = result.toMap().toClasses()["HideIdiom"]!!
            JettaProgram.init("HideIdiom")
            cls.getMethod("__main").invoke(null)
        }
    }
}
