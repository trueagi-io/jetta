package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A multivalued call inside a `let` body makes the enclosing function multivalued.
 *
 * By the time `MarkMultivaluedFunctionsRewriter` runs, a `let` is an immediately applied lambda, and
 * that pass descended into arguments only — so a bag-producing call sitting in a `let` BODY was in a
 * position it never visited, and the function stayed marked scalar. Its descriptor then promised the
 * declared scalar type while the body built a `List`: `areturn` of a `List` against
 * `…/ir/Expression;`, rejected at class load. Per JeTTa's contract a multivalued `-> T` function IS
 * physically `List<T>`, so the fix is to mark it, never to un-mark.
 *
 * The assertion is on the emitted RETURN TYPE rather than on running the program, because that is
 * exactly what the marking decides, and it holds whatever downstream bugs a given shape then hits.
 * The reference `stdlib.metta`'s `filter-atom` is the real case: declared `(-> … Expression)`, body
 * a chain of `let`s over `eval`, and it could not be loaded at all.
 */
class MultivaluedInLetTest : GeneratorTestBase() {

    private fun returnTypeOf(name: String, function: String, code: String): Class<*> =
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach(::println)
                assertTrue(messageCollector.list().isEmpty(), "no diagnostics expected")
                val cls = result.toMap().toClasses()[name]!!
                cls.declaredMethods.first { it.name == function }.returnType
            }

    /**
     * A bag produced in the `let` BODY. The bound expression is the lambda's ARGUMENT, a position the
     * pass always visited, so only this side was ever blind.
     */
    @Test
    fun `a bag bound in a let makes the enclosing function return a List`() {
        val returnType = returnTypeOf(
            "MarkThroughLet", "pick",
            """
                (: two (-> Atom Atom))
                (= (two ${'$'}x) (superpose (${'$'}x ${'$'}x)))
                (: pick (-> Expression Expression))
                (= (pick ${'$'}e) (let ${'$'}a ${'$'}e (two ${'$'}a)))
            """.trimIndent()
        )
        assertEquals(java.util.List::class.java, returnType, "pick must be multivalued")
    }

    /** A lambda in an ARGUMENT slot is a value, not a `let` — its bag belongs to whoever applies it. */
    @Test
    fun `a bag inside a lambda passed as an argument does not spread`() {
        val returnType = returnTypeOf(
            "LambdaArgIsNotALet", "wrap",
            """
                (: two (-> Atom Atom))
                (= (two ${'$'}x) (superpose (${'$'}x ${'$'}x)))
                (: apply1 (-> (-> Atom Atom) Atom Atom))
                (= (apply1 ${'$'}f ${'$'}x) ${'$'}x)
                (: wrap (-> Atom Atom))
                (= (wrap ${'$'}x) (apply1 (\ (${'$'}y) (two ${'$'}y)) ${'$'}x))
            """.trimIndent()
        )
        assertEquals(
            net.singularity.jetta.compiler.frontend.ir.Atom::class.java, returnType,
            "wrap returns the value it was given, not a bag"
        )
    }

    /**
     * One `let` deeper, and run rather than inspected: the outer `let` becomes a lambda whose
     * parameter slot is `Int` (bound to `(+ $n 1)`) while the `$a`s inside the `superpose` tuple
     * resolved to `Atom`. Reading an int slot with `ALOAD` is not a wrong value but an unverifiable
     * method, so this could not load until `generateLoadVar` learned to box-and-wrap a primitive
     * slot for a broad-reference use site.
     */
    @Test
    fun `a bag over a numeric binding one let deeper`() {
        compile(
            "MarkThroughNestedLet.metta",
            """
                (: pick3 (-> Number Number))
                (= (pick3 ${'$'}n)
                  (let ${'$'}a (+ ${'$'}n 1)
                    (let ${'$'}x (superpose (${'$'}a ${'$'}a)) ${'$'}x)))
                !(assertEqualToResult (pick3 1) (2 2))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { registerExternals(it) }.let { (result, messageCollector) ->
            messageCollector.list().forEach(::println)
            assertTrue(messageCollector.list().isEmpty(), "no diagnostics expected")
            val classes = result.toMap().toClasses()
            JettaProgram.init("MarkThroughNestedLet")
            classes["MarkThroughNestedLet"]!!.getMethod("__main").invoke(null)
        }
    }
}
