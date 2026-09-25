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

    /**
     * `superpose` over a tuple of CALLS is the union of what each call answers (casenew): an empty
     * element contributes nothing, a multivalued one all its results. It was a product.
     */
    @Test
    fun `superpose over a tuple of calls is the union of their results`() = run(
        "SuperposeUnion",
        $$"""
            (= (wu1) (empty))
            (= (wu2) (full))
            (= (two) (superpose (a b)))
            !(assertEqual (superpose ((wu1) (wu2))) (full))
            !(assertEqualToResult (superpose ((two) (wu2) c)) (a b (full) c))
            !(assertEqualToResult (superpose ((a b) (c d))) ((a b) (c d)))
        """.trimIndent()
    )

    /**
     * A function's NAME where no function is expected is the symbol (smartdispatch): data holds
     * it, a returned one is applied by the variable-head dispatch. It was eta-expanded into a
     * `JettaLambda` object — ArrayStoreException in a data slot, and an NPE at compile when it
     * was the whole body.
     */
    @Test
    fun `a function name outside a function-typed slot is the symbol`() = run(
        "FunctionNameAsSymbol",
        $$"""
            (= (f $x) (* $x 2))
            (= (g $f $x) (justdata $f $x))
            (= (h $f $x) ($f $x))
            (= (notjustdata $x) f)
            !(assertEqual (g f 2) (justdata f 2))
            !(assertEqual (h f 2) 4)
            !(assertEqual ((notjustdata 42) 21) 42)
        """.trimIndent()
    )

    /** A tuple headed by a tuple is still evaluated inside: `((lol (f 42)))` is `((lol 84))`. */
    @Test
    fun `a call nested in an expression-headed tuple is evaluated`() = run(
        "ExpressionHeadedTupleCall",
        $$"""
            (= (f $x) (* $x 2))
            (= (d) ((lol (f 42))))
            (= (e) ((lol x) y))
            (= (one) (superpose ((superpose (a b)))))
            !(assertEqual (d) ((lol 84)))
            !(assertEqual (e) ((lol x) y))
            !(assertEqualToResult (one) (a b))
        """.trimIndent()
    )

    /**
     * A primitive bound to an `Atom` let-variable is an Atom, and an Atom bound by a pattern reaches
     * an `Int` parameter as its value (iter): a CCE in the let and a VerifyError at the call.
     */
    @Test
    fun `values cross between primitive and Atom bindings`() = run(
        "PrimitiveAtomBindings",
        $$"""
            (= (make-nat-iter) 0)
            (= (iter-next $N) (let* (($X $N) ($Next (+ $N 1))) ($X $Next)))
            !(assertEqual (iter-next 0) (0 1))
            !(assertEqual (let* (($it (make-nat-iter))
                                 (($x1 $it1) (iter-next $it))
                                 (($x2 $it2) (iter-next $it1)))
                                ($x1 $x2)) (0 1))
        """.trimIndent()
    )

    /**
     * A variable free in a pattern-`let`'s VALUE is bound by the unification to the pattern's
     * subterm, and that term is evaluated where the body uses it (letext): `$z` is the `if`.
     */
    @Test
    fun `a pattern-let binds the free variables of its value and evaluates them`() = run(
        "PatternLetValueVariables",
        $$"""
            !(assertEqual (let ($x (42 (if (== $x 2) 43 44))) (3 (42 $z)) (+ $x $z)) 47)
            !(assertEqual (let ($x (42 (if (== $x 2) 43 44))) (3 (42 $z)) ($x $z)) (3 44))
        """.trimIndent()
    )
}
