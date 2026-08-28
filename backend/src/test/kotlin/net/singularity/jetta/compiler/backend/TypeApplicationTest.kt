package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A parenthesised type is not automatically a function type. `(-> $a $b)` is; `($F $a)`,
 * `(Pair $a $b)`, `(List $a)` are type APPLICATIONS — a type constructor applied to arguments —
 * and JeTTa erases those to `Atom` like every other user-defined type.
 *
 * The arrow lowering used to read every nested parenthesised type as an arrow, dropping its head and
 * taking the rest as the arrow's components. So `(: fmap (-> (-> $a $b) ($F $a) ($F $b)))` came out
 * as `(-> (-> Atom Atom) (-> Atom) (-> Atom))`: the container parameter and the RESULT both promised
 * a function. Since every `ArrowType` compiles to `JettaFunction` — an interface, which the verifier
 * does not check — the caller's `(Something 5)` travelled into that slot unchallenged, and the
 * multivalued lift around the recursive call then cast it:
 * `ClassCastException: Expression cannot be cast to JettaFunction` inside `simpleMap`.
 *
 * A green `invoke` IS the assertion. `~` stands for `$` (see `MinimalFoldTest`).
 */
class TypeApplicationTest : GeneratorTestBase() {

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

    /** A type application in a PARAMETER position holds ordinary data. */
    @Test
    fun `a type application as a parameter type takes a constructor term`() {
        run(
            "TypeAppParam",
            """
            (: unbox (-> (~F Atom) Atom))
            (= (unbox (~C ~x)) ~x)
            !(assertEqual (unbox (Box 7)) 7)
            """.trimIndent().v()
        )
    }

    /** And in the RESULT position, which is where a wrong arrow makes the return a function. */
    @Test
    fun `a type application as a return type carries a constructor term`() {
        run(
            "TypeAppReturn",
            """
            (: rebox (-> Atom (~F Atom)))
            (= (rebox ~x) (Box ~x))
            !(assertEqual (rebox 7) (Box 7))
            """.trimIndent().v()
        )
    }

    /** A multi-argument application — the head is not an arrow no matter the arity. */
    @Test
    fun `a two-argument type application is not an arrow`() {
        run(
            "TypeAppPair",
            """
            (: fst (-> (Pair ~a ~b) Atom))
            (= (fst (Pair ~x ~y)) ~x)
            !(assertEqual (fst (Pair 1 2)) 1)
            """.trimIndent().v()
        )
    }

    /**
     * The reference `fmap` in full: the recursive clause makes the call multivalued, so its result is
     * lifted through `simpleMap` — the lift whose `JettaFunction` cast the mis-read result type used
     * to fail.
     */
    @Test
    fun `the reference fmap recurses through the multivalued lift`() {
        run(
            "TypeAppFmap",
            """
            (= ((curry-a ~f ~a) ~b) (~f ~a ~b))
            (: fmap (-> (-> ~a ~b) (~F ~a) (~F ~b)))
            (= (fmap ~f (~C0)) (~C0))
            (= (fmap ~f (~C ~x)) (~C (~f ~x)))
            (= (fmap ~f (~C ~x ~xs)) (~C (~f ~x) (fmap ~f ~xs)))
            !(assertEqual (fmap (curry-a + 2) (Something 5)) (Something 7))
            !(assertEqual
              (fmap (curry-a + 2) (UntypedC 5 (UntypedC 8 (Null))))
              (UntypedC 7 (UntypedC 10 (Null))))
            """.trimIndent().v()
        )
    }
}
