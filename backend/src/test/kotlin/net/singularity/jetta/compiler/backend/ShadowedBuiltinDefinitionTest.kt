package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.resolve.messages.ShadowedByBuiltinMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `=` rule for a name JeTTa grounds natively is unreachable as a call — resolution prefers the
 * builtin — so no method is emitted for it. Compiling one anyway is dead code that can still fail
 * verification, and verification is never local: one bad method takes its whole class down. The
 * reference `stdlib.metta` redefines a dozen builtins on top of hyperon's Rust primitives, and
 * `help!` is written there at two arities, which cannot be represented as one method at all.
 *
 * The rule still reaches the space, so the reflective path is unchanged; only the JVM method goes.
 */
class ShadowedBuiltinDefinitionTest : GeneratorTestBase() {
    private fun String.d() = replace('_', '$')

    @Test
    fun `a redefinition of a builtin emits no method and does not win the call`() {
        val code = """
            (: car-atom (-> Expression Atom))
            (= (car-atom _x) Redefined)
            (= (caller) (car-atom (A B)))
        """.trimIndent().d()
        compile("ShadowedBuiltin.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, mc) ->
                val warning = mc.list().filterIsInstance<ShadowedByBuiltinMessage>()
                assertEquals(1, warning.size, "expected one shadowing warning: ${mc.list()}")
                assertEquals("car-atom", warning[0].name)

                val cls = result.toMap().toClasses()["ShadowedBuiltin"]!!
                assertNull(
                    cls.declaredMethods.firstOrNull { it.name == "car-atom" },
                    "no method may be emitted for a shadowed definition"
                )
                // The call reaches the BUILTIN: `(car-atom (A B))` is `A`, not `Redefined`.
                val out = cls.getMethod("caller").invoke(null)
                assertEquals("A", (out as Symbol).name)
            }
    }

    /**
     * A shadowed rule must not lend its VALUEDNESS to its name either. The reference stdlib
     * redefines `cdr-atom` over `unify`, which is multivalued, so callers were lifted with a
     * `map?` as if the callee returned a bag — while the call links to the builtin, which hands
     * back a single `Expression`. The lift then met an `Expression` where it iterates a `List`
     * (`IncompatibleClassChangeError` in `simpleMap`; in the reference file the same lie reached a
     * lift lambda's descriptor and became a VerifyError at class load).
     *
     * The `if` here is what makes the shape reachable: a multivalued arm homogenizes the whole
     * form, so the leak shows up in the arm as well as at the call.
     */
    @Test
    fun `a shadowed definition does not make its callers multivalued`() {
        val code = """
            (= (cdr-atom _atom)
              (chain (decons-atom _atom) _ht (unify (_head _tail) _ht _tail (Error bad))))
            (: udft (-> Expression Atom))
            (= (udft _params)
              (if (== () _params) (%Undefined%)
                (let _tail-params (cdr-atom _params)
                (let _tail (udft _tail-params)
                  (cons-atom %Undefined% _tail) ))))
            !(assertEqual (udft (a b)) (%Undefined% %Undefined% %Undefined%))
        """.trimIndent().d()
        compile("ShadowedValuedness.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, mc) ->
                val warning = mc.list().filterIsInstance<ShadowedByBuiltinMessage>()
                assertEquals(1, warning.size, "expected one shadowing warning: ${mc.list()}")
                val cls = result.toMap().toClasses()["ShadowedValuedness"]!!
                net.singularity.jetta.runtime.JettaProgram.init("ShadowedValuedness")
                // A wrong answer throws AssertionError out of `__main`, so a green invoke IS the
                // assertion; before the fix this died in `simpleMap` instead.
                cls.getMethod("__main").invoke(null)
            }
    }

    /** A name JeTTa does not ground is untouched — the ordinary case must not be swept up. */
    @Test
    fun `an ordinary definition is still emitted`() {
        val code = """
            (: mine (-> Expression Atom))
            (= (mine _x) Mine)
            (= (caller) (mine (A B)))
        """.trimIndent().d()
        compile("NotShadowed.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, mc) ->
                assertTrue(
                    mc.list().none { it is ShadowedByBuiltinMessage },
                    "no shadowing warning expected: ${mc.list()}"
                )
                val cls = result.toMap().toClasses()["NotShadowed"]!!
                assertTrue(cls.declaredMethods.any { it.name == "mine" }, "mine must be emitted")
                assertEquals("Mine", (cls.getMethod("caller").invoke(null) as Symbol).name)
            }
    }
}
