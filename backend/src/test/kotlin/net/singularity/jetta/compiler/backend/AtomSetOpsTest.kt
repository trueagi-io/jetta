package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The reference stdlib's multiset operations ([net.singularity.jetta.runtime.AtomSetOps]).
 * Expectations were read off hyperon 0.2.10; `multiset_operations.metta` passes there, so its
 * assertions are the specification and the multiplicity cases below were probed separately.
 */
class AtomSetOpsTest : GeneratorTestBase() {

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

    /** `multiset_operations.metta` verbatim. */
    @Test
    fun `the multiset family answers what hyperon answers`() = run(
        "SetFamily",
        """
            !(assertEqual (unique-atom (a b c d d)) (a b c d))
            !(assertEqual (union-atom (a b b c) (b c c d)) (a b b c b c c d))
            !(assertEqual (intersection-atom (a b c c) (b c c c d)) (b c c))
            !(assertEqual (subtraction-atom (a b b c) (b c c d)) (a b))
        """.trimIndent()
    )

    /**
     * They are MULTISET operations: an element survives intersection as many times as BOTH
     * sides hold it, and each occurrence on the right of a subtraction cancels exactly one on
     * the left. Set semantics would answer `(b c)` and `()` here.
     */
    @Test
    fun `multiplicity is preserved, not collapsed`() = run(
        "SetMultiplicity",
        """
            !(assertEqual (intersection-atom (a b c c c) (c c)) (c c))
            !(assertEqual (subtraction-atom (a a a) (a)) (a a))
            !(assertEqual (subtraction-atom (a) (a a)) ())
            !(assertEqual (unique-atom (a a a)) (a))
        """.trimIndent()
    )

    /** Structural elements compare by structure, and empty operands are handled. */
    @Test
    fun `compound elements and empty operands`() = run(
        "SetCompound",
        """
            !(assertEqual (intersection-atom ((f x) b) ((f x) c)) ((f x)))
            !(assertEqual (unique-atom ()) ())
            !(assertEqual (union-atom () (a)) (a))
            !(assertEqual (intersection-atom (a) ()) ())
        """.trimIndent()
    )

    /** A non-expression operand is hyperon's own wording, not a crash. */
    @Test
    fun `a non-expression operand is an error`() = run(
        "SetBadArg",
        """
            !(assertEqualToResult
              (unique-atom a)
              ((Error (unique-atom a) "Atom is not an ExpressionAtom")))
        """.trimIndent()
    )
}
