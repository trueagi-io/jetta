package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A rule that reaches the space only at run time, through `add-atom`, answers the calls to its
 * head (`FunctionRewriter.dynamicHeads` -> `JettaProgram.__reduce`) — the corpus's
 * `spacefunction.metta` and `fibadd.metta`. Before, `(g 3 4)` after
 * `!(add-atom &self (= (g $x $y) (+ $x $y)))` compiled as the data `(g 3 4)`.
 *
 * Each program asserts via `!(assertEqual …)`; a wrong answer throws AssertionError from
 * `__main`, so a green `invoke` IS the assertion.
 */
class RuntimeRuleTest : GeneratorTestBase() {

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

    /** The space answers what it holds when the call runs: a removed rule leaves the call inert. */
    @Test
    fun `a rule added at run time is called and a removed one is not`() = run(
        "RuntimeRuleAddRemove",
        $$"""
            !(add-atom &self (= (f $x $y) (+ $x $y)))
            !(add-atom &self (= (g $x $y) (+ $x $y)))
            !(remove-atom &self (= (f $x $y) (+ $x $y)))
            !(assertEqual (f 3 4) (f 3 4))
            !(assertEqual (g 3 4) 7)
        """.trimIndent()
    )

    /** A rule's answer is its BODY, evaluated — here through recursive calls to the same head. */
    @Test
    fun `a recursive rule added at run time evaluates its body`() = run(
        "RuntimeRuleRecursive",
        $$"""
            !(add-atom &self (= (fib $N) (if (< $N 2) $N (+ (fib (- $N 1)) (fib (- $N 2))))))
            !(assertEqual (fib 10) 55)
        """.trimIndent()
    )

    /** Two rules for one head answer both; a free variable is bound per rule. */
    @Test
    fun `runtime rules are non-deterministic and bind free variables per branch`() = run(
        "RuntimeRuleNondet",
        $$"""
            !(add-atom &self (= (owner cat) alice))
            !(add-atom &self (= (owner dog) bob))
            !(assertEqualToResult (owner $p) (alice bob))
            !(assertEqualToResult (let $o (owner $p) ($p $o)) ((cat alice) (dog bob)))
        """.trimIndent()
    )

    /**
     * `add-atom` stores its atom AS WRITTEN (the reference declares it `Atom`), with the
     * enclosing function's variables substituted. Evaluated, a rule's body ran at the add.
     */
    @Test
    fun `add-atom stores its atom unevaluated`() = run(
        "RuntimeRuleAddInert",
        $$"""
            (= (put $x) (add-atom &self (val $x (+ $x 1))))
            !(put 5)
            !(assertEqual (collapse (match &self (val $a $b) ($a $b))) ((5 (+ 5 1))))
            (= (drop $x) (remove-atom &self (val $x (+ $x 1))))
            !(drop 5)
            !(assertEqual (collapse (match &self (val $a $b) ($a $b))) ())
            !(add-atom &self (= (size $n) (if (< $n 2) small big)))
            !(assertEqual (size 1) small)
            !(assertEqual (size 5) big)
        """.trimIndent()
    )

    /** A `(= (h …) $x)` in a `match` pattern is a query, not a rule: `h` stays data. */
    @Test
    fun `an equality pattern in a match does not make its head runtime-called`() = run(
        "RuntimeRuleMatchPattern",
        $$"""
            !(assertEqual (collapse (match &self (= (shape) $x) $x)) ())
            !(assertEqual (collapse (shape)) ((shape)))
        """.trimIndent()
    )

    /**
     * A scalar builtin over a multivalued call, under another call: the builtin's result is one
     * value per branch, not a bag. `(println! (car-atom (mv)))` mapped `simpleMap` over that value
     * (IncompatibleClassChangeError) — which surfaced once `(status (Goal …))` became a
     * multivalued `__reduce` under `change-state!`.
     */
    @Test
    fun `a scalar builtin over a multivalued argument is not lifted as a bag`() = run(
        "RuntimeRuleScalarOverBag",
        $$"""
            (= (mv) (superpose (((a) b) ((c) d))))
            !(assertEqualToResult (car-atom (car-atom (mv))) (a c))
            !(assertEqualToResult (nop (car-atom (mv))) (() ()))
        """.trimIndent()
    )
}
