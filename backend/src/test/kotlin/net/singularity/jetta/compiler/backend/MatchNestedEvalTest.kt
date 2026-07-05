package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
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

    /**
     * A function body calls another user-defined function with TWO multivalued
     * arguments. This requires the compiler to produce a Cartesian-product
     * flatMap — evaluate both args independently, then call the combining
     * function on every (a, b) pair.
     *
     *   (= (f X) A)
     *   (= (f Y) B)
     *   (= (myPair $a $b) (Pair $a $b))   — quotes into an Expression
     *   (= (combine $x $y) (myPair (f $x) (f $y)))
     *
     * combine(X, Y) should yield [(Pair A B)] — f(X)=[A], f(Y)=[B], myPair(A,B)=[(Pair A B)]
     */
    @Test
    fun `function body with two multivalued arguments`() {
        compile(
            "TwoMultivaluedArgs.metta",
            $$"""
                (= (f X) A)
                (= (f Y) B)

                (: myPair (-> Atom Atom Atom))
                (= (myPair $a $b) (Pair $a $b))

                (= (combine $x $y) (myPair (f $x) (f $y)))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("TwoMultivaluedArgs")

            val combineMethod = classes["TwoMultivaluedArgs"]!!.getMethod(
                "combine", Atom::class.java, Atom::class.java
            )

            // f/myPair/combine are all DETERMINISTIC (exclusive/single clause, only ever
            // called with ground args) so they compile to scalar dispatch: combine(X, Y)
            // reduces f(X)=A, f(Y)=B, myPair(A, B)=(Pair A B) to a single Atom, matching
            // hyperon (a deterministic reduction yields one value, not a singleton bag).
            val result = combineMethod.invoke(null, Symbol("X"), Symbol("Y"))
            assertEquals("(Pair A B)", result.toString(), "Expected scalar (Pair A B) but got: $result")
        }
    }

    /**
     * Cartesian product: both multivalued arguments return MULTIPLE results.
     * The combining function must be called on every (a, b) pair.
     *
     *   (= (f X) A)
     *   (= (f X) B)       — f(X) is multivalued: [A, B]
     *   (= (g Y) C)
     *   (= (g Y) D)       — g(Y) is multivalued: [C, D]
     *   (= (myPair $a $b) (Pair $a $b))
     *   (= (combine $x $y) (myPair (f $x) (g $y)))
     *
     * combine(X, Y) should yield all 4 pairs: [(Pair A C), (Pair A D), (Pair B C), (Pair B D)]
     */
    @Test
    fun `function body with two multivalued arguments - cartesian product`() {
        compile(
            "CartesianProduct.metta",
            $$"""
                (= (f X) A)
                (= (f X) B)

                (= (g Y) C)
                (= (g Y) D)

                (: myPair (-> Atom Atom Atom))
                (= (myPair $a $b) (Pair $a $b))

                (= (combine $x $y) (myPair (f $x) (g $y)))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach { println(it) }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("CartesianProduct")

            val combineMethod = classes["CartesianProduct"]!!.getMethod(
                "combine", Atom::class.java, Atom::class.java
            )

            // combine(X, Y): f(X)=[A, B], g(Y)=[C, D]
            // Expected: all 4 pairs from the cartesian product
            val results = combineMethod.invoke(null, Symbol("X"), Symbol("Y")) as List<*>
            println("combine(X, Y) results: $results")
            println("combine(X, Y) results size: ${results.size}")
            results.forEachIndexed { i, r -> println("  result[$i] = $r (${r?.javaClass})") }
            assertTrue(results.isNotEmpty(), "combine(X, Y) should produce results from both f calls")
            val resultStrings = results.map { it.toString() }.toSet()

            assertEquals(4, results.size, "Expected 4 pairs from 2×2 cartesian product but got: $results")
            assertTrue("(Pair A C)" in resultStrings, "Missing (Pair A C) in $results")
            assertTrue("(Pair A D)" in resultStrings, "Missing (Pair A D) in $results")
            assertTrue("(Pair B C)" in resultStrings, "Missing (Pair B C) in $results")
            assertTrue("(Pair B D)" in resultStrings, "Missing (Pair B D) in $results")
        }
    }

    /**
     * Minimal backward chaining with an And-combiner — the core pattern from
     * the Plato-is-mortal test, stripped to its essence.
     *
     * Two facts reachable via single-step deduction, combined with And:
     *
     *   (Direct P)
     *   (Direct Q)
     *   (= (ded $x) (match &self (Direct $x) T))
     *   (= (ded (And $a $b)) (myAnd (ded $a) (ded $b)))
     *   (= (myAnd T T) T)
     *
     * ded(And P Q) should: destructure → myAnd(ded(P), ded(Q)) → myAnd(T, T) → T
     */
    @Test
    fun `destructured And with two recursive multivalued calls`() {
        compile(
            "NestedEvalAnd.metta",
            $$"""
                (Direct P)
                (Direct Q)

                (= (ded $x) (match &self (Direct $x) T))

                (= (ded (And $a $b)) (myAnd (ded $a) (ded $b)))

                (= (myAnd T T) T)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            messageCollector.list().forEach { println(it) }
            val classes = result.toMap().toClasses()
            JettaProgram.init("NestedEvalAnd")

            val dedMethod = classes["NestedEvalAnd"]!!.getMethod("ded", Atom::class.java)

            // ded(P) -> direct match -> [T]
            val dedP = dedMethod.invoke(null, Symbol("P")) as List<*>
            assertTrue(dedP.any { it.toString() == "T" }, "ded(P) should return [T]")

            // ded(Q) -> direct match -> [T]
            val dedQ = dedMethod.invoke(null, Symbol("Q")) as List<*>
            assertTrue(dedQ.any { it.toString() == "T" }, "ded(Q) should return [T]")

            // ded(And P Q) -> myAnd(ded(P), ded(Q)) -> myAnd(T, T) -> T
            val andExpr = Expression(Symbol("And"), Symbol("P"), Symbol("Q"))
            val dedAnd = dedMethod.invoke(null, andExpr) as List<*>
            assertTrue(
                dedAnd.isNotEmpty(),
                "ded(And P Q) should evaluate both ded(P) and ded(Q), then combine with myAnd"
            )
            assertTrue(
                dedAnd.any { it.toString() == "T" },
                "Expected T from myAnd(T, T) but got: $dedAnd"
            )
        }
    }
}