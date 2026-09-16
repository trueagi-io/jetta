package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A name is only its callee AT ITS OWN ARITY, and valuedness has to be read that way too.
 *
 * `Context.resolveAtom`'s arity guard already leaves an application of the wrong size INERT — data
 * for the runtime to reduce, not a JVM call. The multivalued lift asked a different question: it
 * looked the head up by NAME, found a definition that happened to be multivalued at its own arity,
 * and wrapped the inert term in a `map?`. `simpleMap` was then handed an `Expression` where it
 * wanted a `List` (`IncompatibleClassChangeError`), or the lift's lambda inherited the lie and the
 * class failed to verify — the same failure mode a rule shadowed by a builtin used to produce.
 *
 * The real case is `holfunctions.metta` with the standard library linked: the library's `map-atom`
 * is `(-> Expression Variable Atom Expression)` and multivalued, while the program writes the
 * two-argument `(map-atom (1 2 3) mapfun)` — covered end to end by
 * `AutomaticStdlibImportTest`. What this test guards is the other half: that narrowing the lookup
 * did not switch the lift OFF for a call that does pass the declared number of arguments.
 *
 * `MarkMultivaluedFunctionsRewriter` reads valuedness by name too, and is deliberately NOT
 * narrowed — the comment there records what breaks when it is.
 */
class ArityMismatchValuednessTest : GeneratorTestBase() {

    private fun compiled(name: String, code: String) =
        compile("$name.metta", code, mapImpl, flatMapImpl) { registerExternals(it) }
            .let { (result, messageCollector) ->
                messageCollector.list().forEach(::println)
                result.toMap().toClasses()[name]!!
            }

    /** And the same name AT its own arity still lifts, so the narrowing did not disable the lift. */
    @Test
    fun `the same name at its declared arity is still multivalued`() {
        val cls = compiled(
            "ArityExact",
            """
                (: pick (-> Atom Atom Atom Atom))
                (= (pick ${'$'}a ${'$'}b ${'$'}c) (superpose (${'$'}a ${'$'}b ${'$'}c)))
                (= (full) (pick 1 2 3))
                !(assertEqualToResult (full) (1 2 3))
            """.trimIndent()
        )
        assertTrue(
            List::class.java.isAssignableFrom(cls.declaredMethods.first { it.name == "full" }.returnType),
            "a call at the declared arity still answers a bag",
        )
        JettaProgram.init("ArityExact")
        cls.getMethod("__main").invoke(null)
    }
}
