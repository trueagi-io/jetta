package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression tests for the c3_pln_stv fixes (commit "match/nondet: lift multivalued args
 * over scalar ops; compare Atom operands as double") plus the car-atom/cdr-atom builtins.
 * Each program asserts via `!(assertEqual …)`; a wrong answer throws AssertionError from
 * `__main`, so a green `invoke` IS the assertion.
 */
class GroundedOpsListTest : GeneratorTestBase() {

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
     * Ordering comparison over two Atom operands whose runtime values are Doubles. Before the
     * fix these fell to integer opcodes and truncated 0.8/0.9 to 0, so `(< 0.8 0.9)` was
     * `0 < 0` = false and `mn` always returned its second argument. Both operand orders must
     * now yield the smaller value.
     */
    @Test
    fun `min over double atom operands compares as double`() = run(
        "MinDoubleAtoms",
        $$"""
            (= (mn $a $b) (if (< $a $b) $a $b))
            !(assertEqual (mn 0.8 0.9) 0.8)
            !(assertEqual (mn 0.9 0.8) 0.8)
        """.trimIndent()
    )

    /**
     * A scalar function whose arguments are multivalued BY COMPOSITION — `(proj (pick))`,
     * scalar `proj` over the multivalued `pick` — must be lifted into a cartesian
     * map?/flat-map? so the callee receives one value per branch, not the whole List
     * (was a ClassCast). Multiset-compared, so branch order is irrelevant.
     */
    @Test
    fun `scalar function over composed multivalued args is a cartesian product`() = run(
        "ScalarOverMultivalued",
        $$"""
            (@ pick multivalued)
            (: pick (-> Atom))
            (= (pick) (superpose (1 2)))
            (: proj (-> Atom Atom))
            (= (proj $x) $x)
            (: g2 (-> Atom Atom Atom))
            (= (g2 $a $b) (P $a $b))
            !(assertEqualToResult
               (g2 (proj (pick)) (proj (pick)))
               ((P 1 1) (P 1 2) (P 2 1) (P 2 2)))
        """.trimIndent()
    )

    /** car-atom / cdr-atom on an expression, including nesting. */
    @Test
    fun `car-atom and cdr-atom take head and tail`() = run(
        "CarCdrAtom",
        $$"""
            !(assertEqual (car-atom (a b c)) a)
            !(assertEqual (cdr-atom (a b c)) (b c))
            !(assertEqual (car-atom (cdr-atom (a b c))) b)
        """.trimIndent()
    )
}
