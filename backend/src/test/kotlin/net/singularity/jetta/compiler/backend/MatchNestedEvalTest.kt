package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for nested evaluation in match templates (compiled lambdas).
 *
 * When a match template contains a function call like `(deduce $a)`,
 * the result must be *evaluated*, not returned as a quoted expression.
 * The compiler should compile the template as a lambda that receives
 * bindings and produces evaluated results.
 */
class MatchNestedEvalTest : GeneratorTestBase() {

    private val mapImpl = JvmMethod(
        owner = "net/singularity/jetta/runtime/UtilKt",
        name = "simpleMap",
        descriptor = "(Ljava/util/function/Function;Ljava/util/List;)Ljava/util/List;",
        signature = "<T:Ljava/lang/Object;R:Ljava/lang/Object;>(Ljava/util/function/Function<TT;TR;>;Ljava/util/List<+TT;>;)Ljava/util/List<TR;>;",
    )

    private val flatMapImpl = JvmMethod(
        owner = "net/singularity/jetta/runtime/UtilKt",
        name = "simpleFlatMap",
        descriptor = "(Ljava/util/function/Function;Ljava/util/List;)Ljava/util/List;",
        signature = "<T:Ljava/lang/Object;R:Ljava/lang/Object;>(Ljava/util/function/Function<TT;Ljava/util/List<TR;>;>;Ljava/util/List<+TT;>;)Ljava/util/List<TR;>;",
    )

    /**
     * Simplest case: match template calls a known function with a bound variable.
     *
     * `(lookup $x)` does a space lookup, `(resolve $x)` calls `(lookup $x)` via
     * a match template. The template `(lookup $a)` must be *called*, not quoted.
     *
     *   (Fact Alice)
     *   (Link Alice Bob)
     *   (= (lookup $x) (match &self (Fact $x) T))
     *   (= (resolve $x) (match &self (Link $x $y) (lookup $y)))
     *
     * `resolve(Alice)` should find `(Link Alice Bob)`, then *evaluate* `(lookup Bob)`,
     * which finds `(Fact Bob)` — but Bob isn't a Fact, so the result is [].
     * If we add `(Fact Bob)`, then `resolve(Alice)` should return [T].
     */
    @Test
    fun `match template with single function call is evaluated`() {
        compile(
            "NestedEval1.metta",
            $$"""
                (Fact Alice)
                (Fact Bob)
                (Link Alice Bob)

                (= (lookup $x) (match &self (Fact $x) T))
                (= (resolve $x) (match &self (Link $x $y) (lookup $y)))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("NestedEval1")

            val lookupMethod = classes["NestedEval1"]!!.getMethod("lookup", Atom::class.java)
            val resolveMethod = classes["NestedEval1"]!!.getMethod("resolve", Atom::class.java)

            // lookup(Alice) -> T (direct space match)
            val lookupAlice = lookupMethod.invoke(null, Symbol("Alice")) as List<*>
            assertEquals(1, lookupAlice.size)
            assertEquals("T", lookupAlice[0].toString())

            // resolve(Alice) -> match finds (Link Alice Bob), then evaluates lookup(Bob) -> T
            val resolveAlice = resolveMethod.invoke(null, Symbol("Alice")) as List<*>
            assertTrue(resolveAlice.isNotEmpty(), "resolve(Alice) should evaluate lookup(Bob) and return [T]")
            assertTrue(resolveAlice.any { it.toString() == "T" })
        }
    }

    /**
     * Recursive case: the template calls the same function that triggered the match.
     * This is the minimal backward chaining pattern.
     *
     *   (Direct A)
     *   (Chain B A)
     *   (= (reach $x) (match &self (Direct $x) T))
     *   (= (reach $x) (match &self (Chain $x $y) (reach $y)))
     *
     * `reach(B)` should: match `(Chain B A)` → evaluate `(reach A)` → match `(Direct A)` → T
     */
    @Test
    fun `match template with recursive function call`() {
        compile(
            "NestedEvalRecursive.metta",
            $$"""
                (Direct A)
                (Chain B A)
                (Chain C B)

                (= (reach $x) (match &self (Direct $x) T))
                (= (reach $x) (match &self (Chain $x $y) (reach $y)))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("NestedEvalRecursive")

            val reachMethod = classes["NestedEvalRecursive"]!!.getMethod("reach", Atom::class.java)

            // reach(A) -> direct match -> [T]
            val reachA = reachMethod.invoke(null, Symbol("A")) as List<*>
            assertTrue(reachA.any { it.toString() == "T" })

            // reach(B) -> chain through A -> [T] (1-step recursion)
            val reachB = reachMethod.invoke(null, Symbol("B")) as List<*>
            assertTrue(reachB.isNotEmpty(), "reach(B) should chain through (Chain B A) and evaluate reach(A)")
            assertTrue(reachB.any { it.toString() == "T" })

            // reach(C) -> chain through B -> chain through A -> [T] (2-step recursion)
            val reachC = reachMethod.invoke(null, Symbol("C")) as List<*>
            assertTrue(reachC.isNotEmpty(), "reach(C) should chain C->B->A")
            assertTrue(reachC.any { it.toString() == "T" })
        }
    }
}