package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A META-typed argument (`Expression`, or an `Atom` the body also uses as a term) is handed over as
 * the term and evaluated where the body needs its value — `FunctionRewriter.holdMetaParams` wraps
 * each value occurrence in `(__force …)`. Every expected answer here was measured on `metta-repl`.
 *
 * A held term that calls a USER function is forced through the program's linker table, which this
 * harness does not write — that case is `HeldMetaParamLinkTest` in the compiler module.
 */
class HeldMetaParamTest : GeneratorTestBase() {

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

    private fun String.d() = replace('_', '$')

    /** The corpus `functiontypes` shape: the tuple element is forced, not the call-site argument. */
    @Test
    fun `an Expression argument is evaluated where the body needs it`() {
        run(
            "HeldTupleElement",
            """
            (: wu1 (-> Number Expression Expression))
            (= (wu1 _a _b) (42 _a _b))
            !(assertEqual (wu1 (+ 2 4) (+ 4 2)) (42 6 6))
            """.trimIndent().d()
        )
    }

    @Test
    fun `an Expression argument used in arithmetic is forced`() {
        run(
            "HeldArithmetic",
            """
            (: e1 (-> Expression Number))
            (= (e1 _x) (+ _x 1))
            !(assertEqual (e1 (+ 1 2)) 4)
            """.trimIndent().d()
        )
    }

    /** A function whose result is `Atom` returns the term: nothing evaluates it. */
    @Test
    fun `an Atom function returns its held argument as written`() {
        run(
            "HeldAtomResult",
            """
            (: q (-> Atom Atom))
            (= (q _x) _x)
            (: q2 (-> Atom %Undefined%))
            (= (q2 _x) (foo _x))
            !(assertEqualToResult (q (+ 1 2)) ((+ 1 2)))
            !(assertEqual (q2 (+ 1 2)) (foo 3))
            """.trimIndent().d()
        )
    }

    /** The corpus `myinterpreter` shape: the code reaches `metta` as a term and runs there. */
    @Test
    fun `held code is run by metta`() {
        run(
            "HeldInterpreter",
            """
            (: interp (-> Expression %Undefined%))
            (= (interp _code) (metta _code %Undefined% &self))
            (= (w) 42)
            (= (v) 43)
            !(assertEqual (interp (if (== 1 1) (w) (v))) 42)
            !(assertEqual (interp (if (== 1 2) (w) (v))) 43)
            """.trimIndent().d()
        )
    }

    /**
     * A held parameter handed to a callee in the SAME file that holds its own: the callee wants the
     * term, which the parameter already is — quoting the `(__force …)` instead never reached `()`.
     * The tail is bound by `let` first: written as the argument, `(cdr-atom _e)` is itself the term
     * the held parameter receives, and the reference recurses forever on it too.
     */
    @Test
    fun `a held parameter reaches a held parameter of the same file as the term`() {
        run(
            "HeldToHeld",
            """
            (: noreduce-eq (-> Atom Atom Bool))
            (= (noreduce-eq _a _b) (== (quote _a) (quote _b)))
            (: walk (-> Expression Atom))
            (= (walk _e) (if (noreduce-eq _e ()) done (let _t (cdr-atom _e) (walk _t))))
            !(assertEqual (walk (1 2 3)) done)
            """.trimIndent().d()
        )
    }

    /** An `==` operand is quoted, and the forced value, not the `(__force …)` call, is compared. */
    @Test
    fun `a forced operand of an equality is compared by value`() {
        run(
            "HeldEquality",
            """
            (: udft (-> Expression Atom))
            (= (udft _params)
              (if (== () _params) (%Undefined%)
                (let _tail (udft (cdr-atom _params)) (cons-atom %Undefined% _tail))))
            !(assertEqual (udft (a b)) (%Undefined% %Undefined% %Undefined%))
            """.trimIndent().d()
        )
    }
}
