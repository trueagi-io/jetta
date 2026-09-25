package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `(== (quote $a) (quote $b))` — how the reference stdlib writes `noreduce-eq`, the equality that
 * compares two atoms without reducing them.
 *
 * `==` over reference operands has a pattern path: when the RIGHT side is an expression carrying
 * variables it is matched structurally rather than compared, which is what makes
 * `(== $x (And $p $q))` a shape test. `(quote $b)` is also an expression carrying a variable, so it
 * took that path — the right side became the literal term `(quote <value>)` while the left side was
 * peeled to the value itself, and the comparison was therefore **False for every input**, silently.
 * `for-each-in-atom` tests termination with `noreduce-eq`, so it recursed until the reduction
 * pattern blew the stack (`StackOverflowError` in `PatternKey`, nowhere near the cause).
 *
 * A green `invoke` IS the assertion. `~` stands for `$` (see `MinimalFoldTest`).
 */
class QuotedComparisonTest : GeneratorTestBase() {

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

    /** The reference `noreduce-eq`, over each kind of atom — and it must still say False. */
    @Test
    fun `quoted equality holds for equal atoms and fails for different ones`() {
        run(
            "NoreduceEq",
            """
            (: noreduce-eq (-> Atom Atom Bool))
            (= (noreduce-eq ~a ~b) (== (quote ~a) (quote ~b)))
            !(assertEqual (noreduce-eq () ()) True)
            !(assertEqual (noreduce-eq a a) True)
            !(assertEqual (noreduce-eq (1) (1)) True)
            !(assertEqual (noreduce-eq (f X) (f X)) True)
            !(assertEqual (noreduce-eq (1) ()) False)
            !(assertEqual (noreduce-eq a b) False)
            """.trimIndent().v()
        )
    }

    /**
     * The empty expression is what a `cdr-atom` walk terminates on, so pin it computed too — as a
     * VALUE, bound first. Written as an argument the application itself is what a meta-typed
     * parameter receives, and it is not `()` (measured on `metta-repl`: False).
     */
    @Test
    fun `quoted equality recognises a computed empty expression`() {
        run(
            "NoreduceEqComputed",
            """
            (: noreduce-eq (-> Atom Atom Bool))
            (= (noreduce-eq ~a ~b) (== (quote ~a) (quote ~b)))
            !(assertEqual (let ~t (cdr-atom (3)) (noreduce-eq ~t ())) True)
            !(assertEqual (let ~t (cdr-atom (2 3)) (noreduce-eq ~t ())) False)
            !(assertEqual (noreduce-eq (cdr-atom (3)) ()) False)
            """.trimIndent().v()
        )
    }

    /**
     * The PATTERN path is deliberately kept: a right-hand expression with free variables is a shape
     * test, not a comparison. This is the behaviour the quote exclusion had to leave intact.
     */
    @Test
    fun `a variable-bearing right operand is still matched as a pattern`() {
        run(
            "EqPatternPath",
            """
            (: is-and (-> Atom Bool))
            (= (is-and ~x) (== ~x (And ~p ~q)))
            !(assertEqual (is-and (And X Y)) True)
            !(assertEqual (is-and (Or X Y)) False)
            """.trimIndent().v()
        )
    }

    /**
     * The reference `for-each-in-atom` shape: a `cdr-atom` recursion whose base case is
     * `noreduce-eq`, applying a variable-headed function to every element. A base case that never
     * holds means unbounded recursion, which is how the defect actually presented — so reaching the
     * unit atom at all IS the assertion here.
     *
     * The side effects are asserted by the `C10` stdlib probe rather than here: in this in-process
     * harness the space is the live compile-time one and the var-headed `(~func ~head)` dispatch
     * goes through the JIT with a live env, where the writes do not land — the same program run
     * through the CLI, and the reference `for-each-in-atom` through `hstdlib`, both record all three.
     * That divergence is its own defect, not this one.
     */
    @Test
    fun `a cdr-atom recursion terminating on quoted equality reaches the base case`() {
        run(
            "ForEachInAtom",
            """
            (: noreduce-eq (-> Atom Atom Bool))
            (= (noreduce-eq ~a ~b) (== (quote ~a) (quote ~b)))
            (: for-each (-> Expression Atom Atom))
            (= (for-each ~expr ~func)
              (if (noreduce-eq ~expr ())
                ()
                (let ~head (car-atom ~expr)
                  (let ~tail (cdr-atom ~expr)
                    (let ~ignored (~func ~head)
                      (for-each ~tail ~func))))))
            (= (note ~x) (pair seen ~x))
            !(assertEqual (for-each (1 2 3) note) ())
            """.trimIndent().v()
        )
    }
}
