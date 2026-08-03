package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression tests for the multivalued-lift box — the increment that closed
 * c1_grounded_basic and f1's first assertion. Each program asserts via
 * `!(assertEqual …)` / `!(assertEqualToResult …)`; a wrong answer throws AssertionError
 * from `__main`, so a green `invoke` IS the assertion.
 *
 * The five defects covered here, in commit order:
 *  - an arithmetic body must box its result when the return slot is a reference;
 *  - a lift must not be registered inside an inert-Atom argument (`get-type`'s);
 *  - a lift whose body yields a bag must flat-map, not map;
 *  - a barrier's COMPOUND argument produces its own bag (the wrap belongs inside the
 *    argument, not around the assertion);
 *  - a one-element tuple over a bag-valued application is code, not data.
 */
class MultivaluedBarrierLiftTest : GeneratorTestBase() {

    private fun run(name: String, code: String) {
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach(::println)
                assertTrue(messageCollector.list().isEmpty())
                val classes = result.toMap().toClasses()
                JettaProgram.init(name)
                classes[name]!!.getMethod("__main").invoke(null)
            }
    }

    /**
     * c1 :116. The argument `(+ (superpose (1 2 3)) 1)` is a COMPOUND under a barrier: it must
     * produce the whole bag `(2 3 4)` itself. The lift used to bubble past `assertEqual` and
     * wrap the assertion, running it three times with a scalar against a three-element bag —
     * which "passed" only because the comparison never got a chance to be right.
     */
    @Test
    fun `arithmetic over a superpose argument yields a bag to the barrier`() = run(
        "BarrierArithmeticSuperpose",
        $$"""
            !(assertEqual
              (+ (superpose (1 2 3)) 1)
              (superpose (2 3 4)))
        """.trimIndent()
    )

    /**
     * c1 :121, the same shape over a user-defined multivalued function rather than `superpose`.
     */
    @Test
    fun `arithmetic over a multivalued call yields a bag to the barrier`() = run(
        "BarrierArithmeticUserCall",
        $$"""
            (= (bin) 0)
            (= (bin) 1)
            !(assertEqualToResult
              (+ 1 (bin))
              (1 2))
        """.trimIndent()
    )

    /**
     * The lifted lambda's body is arithmetic — `(\ ($v:Atom) (+ $v 1))` — so it leaves a raw
     * primitive where the emitted method returns `Atom`. `generateMod` and friends emit their
     * own return and never reach `coerceForReturn`; without the box this VerifyErrors before
     * `main` is even entered. `%` and `/` exercise the two other early-return paths.
     */
    @Test
    fun `non-additive arithmetic over a bag also boxes its lifted result`() = run(
        "BarrierModAndDivide",
        $$"""
            !(assertEqualToResult
              (% (superpose (21 22)) 17)
              (4 5))
            !(assertEqualToResult
              (/ (superpose (4.0 8.0)) 2.0)
              (2.0 4.0))
        """.trimIndent()
    )

    /**
     * c1 :162 in miniature: a `let` over a multivalued generator, filtering with `(superpose ())`
     * as the empty branch. The `let` desugars to an applied lambda, whose head is neither a
     * Symbol nor a defined function — so its argument's lift had nowhere to land but the barrier.
     */
    @Test
    fun `a let over a multivalued generator filters inside the barrier`() = run(
        "BarrierLetFilter",
        $$"""
            (= (bit) 0)
            (= (bit) 1)
            !(assertEqualToResult
              (let $b (bit) (if (== $b 1) $b (superpose ())))
              (1))
        """.trimIndent()
    )

    /**
     * The lift's body is an applied lambda that itself returns a bag, so the innermost wrap must
     * FLAT_MAP_. Rewriting rebuilds the body under a fresh id and loses the multivalued mark
     * collection put on it, so the decision reads the lambda's declared `Atom*` return instead.
     * Mapped rather than flattened, this answered `[[1, 1], [2, 2]]`.
     */
    @Test
    fun `a let whose body is a system multivalued call flattens`() = run(
        "BarrierLetFlatten",
        $$"""
            !(assertEqualToResult
              (let $x (superpose (1 2)) (superpose ($x $x)))
              (1 1 2 2))
        """.trimIndent()
    )

    /**
     * d4 :19. `get-type`'s operand is declared inert-Atom and must reach the callee UN-reduced;
     * `rewriteExpression` leaves it raw, but collection used to register a lift for the
     * multivalued `(Mortal Plato)` inside it. The stray wrap re-evaluated the very term that was
     * meant to stay inert — harmless while it landed outside the assertion, `[[Type]]` once the
     * barrier argument got a scope of its own.
     */
    @Test
    fun `an inert-Atom argument registers no lift`() = run(
        "InertAtomArgumentNoLift",
        $$"""
            (: Entity Type)
            (: Plato Entity)
            (: Mortal (-> Entity Type))
            (= (Mortal $x) (mortal-of $x))
            !(assertEqual
              (get-type (Mortal Plato))
              Type)
        """.trimIndent()
    )

    /**
     * d1 :98. A pattern-`let` compiles to `letMatch`, a SYSTEM multivalued function — a head the
     * flatten decision did not recognise, so the wrap nested and the answer came back
     * `[[Number]]`.
     */
    @Test
    fun `a pattern let over get-type flattens`() = run(
        "BarrierPatternLetGetType",
        $$"""
            (: Cons (-> $t (List $t) (List $t)))
            (: Nil (List $t))
            !(assertEqual
              (let (List $t) (get-type (Cons 5 (Cons 6 Nil))) $t)
              Number)
        """.trimIndent()
    )

    /**
     * f1's first assertion. `((let …))` is a one-element TUPLE whose element is a bag-valued
     * application: assert lowering used to call any Expression head symbolic data and quote it,
     * which stored the lambda object into an `Atom[]` (ArrayStoreException). Collection also
     * never visited an Expression head — `atoms.drop(1)` skips it. The space is empty here, so
     * the bag is empty and the whole form yields nothing.
     */
    @Test
    fun `a one-element tuple over a bag-valued application lifts`() = run(
        "TupleOverBagValuedApplication",
        $$"""
            !(assertEqualToResult
              ((let $x (get-atoms &self) (get-type $x)))
              ())
        """.trimIndent()
    )
}
