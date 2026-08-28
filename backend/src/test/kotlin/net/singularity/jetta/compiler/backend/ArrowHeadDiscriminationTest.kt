package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A parameter declared with an arrow type promises a function, and JeTTa compiles the promise into a
 * direct `JettaFunction.apply`. The promise is not enforceable: `JettaFunction` is an INTERFACE, and
 * the verifier does not check interface types, so a value that is not a function reaches such a slot
 * unchallenged.
 *
 * And in MeTTa it routinely does. `(: fmap (-> (-> $a $b) ($F $a) ($F $b)))` declares an arrow
 * parameter; `(fmap (curry-a + 2) (Something 5))` passes a curried TERM, because `curry-a` is
 * defined by a space `(= …)` rule and never becomes a compiled lambda — its partial application is
 * an inert `Expression`. The call then died as
 * `IncompatibleClassChangeError: Expression does not implement JettaFunction`.
 *
 * So the head is discriminated at run time: a real function takes the interface call, anything else
 * goes to the `JettaCallSite` dispatcher, which rewrites it by the space rules. Both shapes have to
 * keep working — routing every arrow-typed head through the dispatcher instead loses the genuine
 * lambdas.
 *
 * A green `invoke` IS the assertion. `~` stands for `$` (see `MinimalFoldTest`).
 */
class ArrowHeadDiscriminationTest : GeneratorTestBase() {

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

    private fun String.v() = replace('~', '$')

    /** The fast path: the head really is a compiled lambda, invoked through the interface. */
    @Test
    fun `an arrow-declared head that is a compiled lambda is invoked directly`() {
        run(
            "ArrowHeadLambda",
            """
            (: apply-it (-> (-> Atom Atom) Atom Atom))
            (= (apply-it ~f ~x) (~f ~x))
            !(assertEqual (apply-it (\ (~n) (+ ~n 1)) 2) 3)
            """.trimIndent().v()
        )
    }

    /** The defect: the same slot holding a curried TERM, which no interface call can invoke. */
    @Test
    fun `an arrow-declared head that is a curried term is dispatched`() {
        run(
            "ArrowHeadCurried",
            """
            (= ((curry-a ~f ~a) ~b) (~f ~a ~b))
            (: apply-it (-> (-> Atom Atom) Atom Atom))
            (= (apply-it ~f ~x) (~f ~x))
            !(assertEqual (apply-it (curry-a + 2) 3) 5)
            """.trimIndent().v()
        )
    }

    /** Both shapes reach the same call site in one program, so the discrimination is per-call. */
    @Test
    fun `a lambda and a curried term reach the same arrow-declared call site`() {
        run(
            "ArrowHeadBoth",
            """
            (= ((curry-a ~f ~a) ~b) (~f ~a ~b))
            (: apply-it (-> (-> Atom Atom) Atom Atom))
            (= (apply-it ~f ~x) (~f ~x))
            !(assertEqual (apply-it (\ (~n) (* ~n 10)) 4) 40)
            !(assertEqual (apply-it (curry-a - 7) 3) 4)
            """.trimIndent().v()
        )
    }

    /**
     * The call nested in a DATA CONSTRUCTOR, which is where the reference `fmap-i` puts it:
     * `(= (fmap-i $f (Right $x)) (Right ($f $x)))`. Nothing resolves an arrow-headed application,
     * so the quote path took it for data and answered `(Right ((curry-a - 7) 3))`.
     */
    @Test
    fun `an arrow-declared call inside a constructor is evaluated`() {
        run(
            "ArrowHeadInConstructor",
            """
            (= ((curry-a ~f ~a) ~b) (~f ~a ~b))
            (: wrap (-> (-> Atom Atom) Atom Atom))
            (= (wrap ~f ~x) (Wrapped (~f ~x)))
            !(assertEqual (wrap (curry-a - 7) 3) (Wrapped 4))
            """.trimIndent().v()
        )
    }

    /** The reference `fmap-i` over `Either`, verbatim in shape. */
    @Test
    fun `the reference fmap-i maps a curried term over a constructor`() {
        run(
            "ArrowHeadFmapI",
            """
            (= ((curry-a ~f ~a) ~b) (~f ~a ~b))
            (: fmap-i (-> (-> ~a ~b) (~F ~a) (~F ~b)))
            (= (fmap-i ~f (Left ~x)) (Left (~f ~x)))
            (= (fmap-i ~f (Right ~x)) (Right (~f ~x)))
            !(assertEqual (fmap-i (curry-a - 7) (Right 3)) (Right 4))
            !(assertEqual (fmap-i (curry-a + 2) (Left 5)) (Left 7))
            """.trimIndent().v()
        )
    }
}
