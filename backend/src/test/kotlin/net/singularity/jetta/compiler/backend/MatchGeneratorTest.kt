package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchGeneratorTest : GeneratorTestBase() {
    @Test
    fun `0 args match, 2 branches`() =
        compile(
            "TwoBranches0Args.metta",
            """
                (: foo (-> Int))
                (= (foo) 0)
                (= (foo) 1)
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["TwoBranches0Args"]!!.getMethod("foo").invoke(null)
            assertEquals(listOf(0, 1), value)
        }

    @Test
    fun `1 args match, 2 branches`() =
        compile(
            "TwoBranches1Arg.metta",
            """
                (: foo (-> Int Int))
                (= (foo 10) 0)
                (= (foo _x) (+ _x 1))
                """.trimIndent().replace('_', '$')
        ).let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val value = classes["TwoBranches1Arg"]!!.getMethod("foo", Int::class.java).invoke(null, 10)
            assertEquals(listOf(0, 11), value)
        }

    @Test
    fun `symbol constant matching in branches`() =
        compile(
            "SymbolMatch.metta",
            $$"""
            (: myAnd (-> Atom Atom Atom))
            (= (myAnd T T) T)
            (= (myAnd $x $y) F)
        """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            val m = classes["SymbolMatch"]!!.getMethod("myAnd", Atom::class.java, Atom::class.java)
            val r = m.invoke(null, Symbol("T"), Symbol("T")) as List<*>
            assertTrue(r.any { it.toString() == "T" })
        }

    @Test
    fun `destructured binding extracts nested variables and quotes constructor`() {
        // (= (wrap (Pair $a $b)) (Pair $b $a))  — swaps the two fields
        // (= (wrap $x) $x)                       — fallback
        // Pair is NOT a declared function — the body should quote it as an Expression
        compile(
            "DestructureQuote.metta",
            $$"""
                (: wrap (-> Atom Atom))
                (= (wrap (Pair $a $b)) (Pair $b $a))
                (= (wrap $x) $x)
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach { println(it) }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("DestructureQuote")

            val method = classes["DestructureQuote"]!!.getMethod("wrap", Atom::class.java)

            // wrap(Pair A B):
            //   Branch 1 matches (Pair $a $b) → (Pair B A) — swapped
            //   Branch 2 matches $x            → (Pair A B) — identity
            val pairExpr = Expression(Symbol("Pair"), Symbol("A"), Symbol("B"))
            val results = method.invoke(null, pairExpr) as List<*>
            assertEquals(2, results.size, "Both branches should match, got: $results")
            assertTrue(results.any { it.toString() == "(Pair B A)" },
                "Expected (Pair B A) from destructured branch but got: $results")
            assertTrue(results.any { it.toString() == "(Pair A B)" },
                "Expected (Pair A B) from fallback branch but got: $results")

            // wrap(Leaf): only branch 2 matches ($x) → Leaf
            val leaf = Symbol("Leaf")
            val fallback = method.invoke(null, leaf) as List<*>
            assertEquals(1, fallback.size, "Only fallback should match for Leaf")
            assertTrue(fallback.any { it.toString() == "Leaf" })
        }
    }

    @Test
    fun `destructured binding with And pattern quotes constructor`() {
        // Mirrors (= (deduce (And $a $b)) (And (deduce $a) (deduce $b))) pattern
        // but without defining And as a function — body should be quoted
        compile(
            "DestructureAndQuote.metta",
            $$"""
                (: identity (-> Atom Atom))
                (= (identity (And $a $b)) (And (identity $a) (identity $b)))
                (= (identity $x) $x)
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach { println(it) }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("DestructureAndQuote")

            val method = classes["DestructureAndQuote"]!!.getMethod("identity", Atom::class.java)

            // identity(Leaf): only branch 2 matches → [Leaf]
            val leaf = Symbol("Leaf")
            val leafResults = method.invoke(null, leaf) as List<*>
            assertEquals(1, leafResults.size)
            assertEquals("Leaf", leafResults[0].toString())

            // identity(And X Y): both branches match
            //   Branch 1: destructures → (And (identity X) (identity Y)) → (And X Y)
            //   Branch 2: fallback    → (And X Y) — identity
            val andExpr = Expression(Symbol("And"), Symbol("X"), Symbol("Y"))
            val results = method.invoke(null, andExpr) as List<*>
            assertTrue(results.size >= 1, "Expected at least 1 result from identity(And X Y)")
            assertTrue(results.any { it.toString() == "(And X Y)" },
                "Expected (And X Y) in results but got: $results")
        }
    }

    @Test
    fun `destructured binding with multiple nested params`() {
        // Two parameters both with nested patterns
        // MeTTa evaluates all matching branches
        compile(
            "DestructureMultiParam.metta",
            $$"""
                (: combine (-> Atom Atom Atom))
                (= (combine (Pair $a $b) (Pair $c $d)) (Pair $a $d))
                (= (combine $x $y) $x)
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach { println(it) }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("DestructureMultiParam")

            val method = classes["DestructureMultiParam"]!!.getMethod(
                "combine", Atom::class.java, Atom::class.java
            )

            // combine(Pair A B, Pair C D):
            //   Branch 1: destructures both → (Pair A D)
            //   Branch 2: fallback          → (Pair A B) (= $x)
            val pair1 = Expression(Symbol("Pair"), Symbol("A"), Symbol("B"))
            val pair2 = Expression(Symbol("Pair"), Symbol("C"), Symbol("D"))
            val results = method.invoke(null, pair1, pair2) as List<*>
            assertEquals(2, results.size, "Both branches should match, got: $results")
            assertTrue(results.any { it.toString() == "(Pair A D)" },
                "Expected (Pair A D) from destructured branch but got: $results")
            assertTrue(results.any { it.toString() == "(Pair A B)" },
                "Expected (Pair A B) from fallback branch but got: $results")

            // combine(X, Y) with non-matching args: only branch 2
            val fallback = method.invoke(null, Symbol("X"), Symbol("Y")) as List<*>
            assertEquals(1, fallback.size)
            assertTrue(fallback.any { it.toString() == "X" })
        }
    }

    @Test
    fun `destructured binding - extract first element from pattern`() {
        // Simple extraction: return $a from (Tag $a $b)
        // MeTTa evaluates all branches
        compile(
            "DestructureFallback.metta",
            $$"""
                (: first (-> Atom Atom))
                (= (first (Tag $a $b)) $a)
                (= (first $x) $x)
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach { println(it) }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("DestructureFallback")

            val method = classes["DestructureFallback"]!!.getMethod("first", Atom::class.java)

            // first(Tag X Y):
            //   Branch 1: extracts $a=X → X
            //   Branch 2: fallback $x   → (Tag X Y)
            val tagExpr = Expression(Symbol("Tag"), Symbol("X"), Symbol("Y"))
            val matched = method.invoke(null, tagExpr) as List<*>
            assertEquals(2, matched.size, "Both branches should match, got: $matched")
            assertTrue(matched.any { it.toString() == "X" },
                "Expected X from destructured \$a but got: $matched")
            assertTrue(matched.any { it.toString() == "(Tag X Y)" },
                "Expected (Tag X Y) from fallback but got: $matched")

            // first(Leaf): only branch 2 matches
            val leaf = Symbol("Leaf")
            val fallback = method.invoke(null, leaf) as List<*>
            assertEquals(1, fallback.size)
            assertEquals("Leaf", fallback[0].toString())
        }
    }
}