package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.FunctionDefinition
import net.singularity.jetta.compiler.frontend.ir.GroundedType
import net.singularity.jetta.compiler.frontend.resolve.messages.CannotResolveSymbolMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An application whose head is a symbol with no definition is DATA. The reference interpreter has
 * no notion of an unresolved symbol — a head with no `=` rule simply leaves the form inert — so
 * the "can not resolve symbol" diagnostic survives only where JeTTa cannot REPRESENT that: a
 * declared `Int` result is an `ireturn`, which has no room for a quoted Expression. See
 * `Context.demandsGroundedResult`.
 *
 * The condition this replaced keyed on the enclosing function's PARAMS (some param typed `Atom`,
 * or a `Match` body), which rested on type erasure rather than on meaning: `Number` and `Type` are
 * absent from [GroundedType] and erase to `Atom`, so they were accepted, while `Expression`, `Bool`
 * and `String` — no less data-carrying — were rejected. Same programs, opposite answers.
 *
 * `ResolveTest.cannotResolveSymbol` pins the other side of the boundary (an `Int` result).
 */
class DataConstructorHeadTest : BaseFrontendTest() {

    private fun Pair<ParsedSource, MessageCollector>.assertNoUnresolvedHead() =
        assertTrue(
            second.list().none { it is CannotResolveSymbolMessage },
            "unexpected diagnostics: ${second.list()}"
        )

    /** The result type erases to `Atom`, so an inert data application is representable. */
    @Test
    fun dataHeadUnderAnErasedResultIsSilent() =
        resolve(
            "ErasedResult.metta",
            """
            (: z (-> Expression Type))
            (= (z _x) (A))
            """.trimIndent().replace('_', '$')
        ).let { result ->
            result.assertNoUnresolvedHead()
            val func = result.first.code[0] as FunctionDefinition
            // Suppressing the message must not change the classification: still data.
            assertEquals(GroundedType.ATOM, func.body.type)
        }

    /**
     * A concrete PARAMETER type is irrelevant — it is the result position that has to hold the
     * value. `String` and `Bool` params used to reject the very same body that a `Number` param
     * (erased to `Atom`) accepted.
     */
    @Test
    fun aConcreteParameterTypeDoesNotMakeDataAnError() {
        resolve(
            "StringParam.metta",
            """
            (: q (-> String Type))
            (= (q _x) (A))
            """.trimIndent().replace('_', '$')
        ).assertNoUnresolvedHead()

        resolve(
            "BoolParam.metta",
            """
            (: q (-> Bool Type))
            (= (q _x) (A B))
            """.trimIndent().replace('_', '$')
        ).assertNoUnresolvedHead()
    }

    /** No parameters at all: there was nothing for the old params-based condition to look at. */
    @Test
    fun anUntypedZeroArgFunctionMayReturnData() =
        resolve(
            "ZeroArg.metta",
            """
            (= (g) (A))
            """.trimIndent().replace('_', '$')
        ).assertNoUnresolvedHead()

    /**
     * The shape that blocked the reference `stdlib.metta` (`undefined-doc-function-type`): a
     * one-element data tuple `(%Undefined%)` — a TYPE CONSTANT in head position — under an
     * `Expression` parameter.
     */
    @Test
    fun theReferenceStdlibUndefinedDocFunctionTypeResolves() =
        resolve(
            "UndefinedDocFunctionType.metta",
            """
            (: undefined-doc-function-type (-> Expression Type))
            (= (undefined-doc-function-type _params)
              (if (== () _params) (%Undefined%)
                (let _tail (undefined-doc-function-type (cdr-atom _params))
                  (cons-atom %Undefined% _tail))))
            """.trimIndent().replace('_', '$')
        ).assertNoUnresolvedHead()

    /**
     * The boundary: a `String` result is a reference to `java.lang.String`, not a slot an inert
     * Expression can occupy, so the head is still reported rather than compiled into a class that
     * fails verification.
     */
    @Test
    fun anUnresolvedHeadInAPrimitiveResultIsStillReported() =
        resolve(
            "PrimitiveResult.metta",
            """
            (: s (-> Int String))
            (= (s _x) (A))
            """.trimIndent().replace('_', '$')
        ).let { (_, messageCollector) ->
            val reported = messageCollector.list().filterIsInstance<CannotResolveSymbolMessage>()
            assertEquals(1, reported.size, "messages: ${messageCollector.list()}")
            assertEquals("A", reported[0].symbol)
        }
}
