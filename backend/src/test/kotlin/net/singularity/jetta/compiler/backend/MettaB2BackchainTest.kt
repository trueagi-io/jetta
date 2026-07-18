package net.singularity.jetta.compiler.backend

import kotlin.test.Test

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import net.singularity.jetta.runtime.JettaProgram

class MettaB2BackchainTest : GeneratorTestBase() {
    @Test
    fun `match inside function definition`() {
        compile(
            "FrogMatch.metta",
            $$"""
                ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                ; `match` can be used inside equalities, which is typically
                ; used for querying and reasoning over declarative knowledge
                ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                ; Fact
                (Frog Sam)
                (= (frog $x) (match &self (Frog $x) T))
            """.trimIndent()
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("FrogMatch")

            // (Frog Sam) is not reduced; it stays in the space as a declaration
            // (frog Sam) should use that declaration and return [T]
            val frogMethod = classes["FrogMatch"]!!.getMethod("frog", Atom::class.java)

            // frog(Sam) -> match finds (Frog Sam) in space, returns T
            val samResult = frogMethod.invoke(null, Symbol("Sam")) as List<*>
            assertEquals(1, samResult.size)
            assertEquals("T", samResult[0].toString())

            // frog(Fritz) -> no (Frog Fritz) in space, returns []
            val fritzResult = frogMethod.invoke(null, Symbol("Fritz")) as List<*>
            assertTrue(fritzResult.isEmpty())
        }
    }

    @Test
    fun `backward chaining deduction - Plato is mortal`() {
        compile(
            "BackchainDeduction.metta",
            $$"""
                ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                ; The result of matching is also chained
                ; Example from OpenCog Classic wiki on PLN Backward Chaining
                ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                ; Some facts in the knowledge base
                (Evaluation (philosopher Plato))
                (Evaluation (likes-to-wrestle Plato))

                ; An implication rule
                (Implication
                   (And (Evaluation (philosopher $x))
                        (Evaluation (likes-to-wrestle $x)))
                   (Evaluation (human $x)))

                ; Another implication rule
                (Implication
                   (Evaluation (human $x))
                   (Evaluation (mortal $x)))

                ; Deduction case when the desired evaluation is present in
                ; the knowledge base
                (= (deduce (Evaluation ($P $x)))
                   (match &self (Evaluation ($P $x)) T))

                ; Deduction case when the desired evaluation is the result
                ; of an implication, which implies a recursion
                (= (deduce (Evaluation ($P $x)))
                   (match &self
                     (Implication $a (Evaluation ($P $x)))
                     (deduce $a)))

                ; Deduction case for generic "And" expressions;
                ; also recursive
                (= (deduce (And $a $b))
                   (And (deduce $a) (deduce $b)))

                ; True & True = True
                (= (And T T) T)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("BackchainDeduction")

            val deduceMethod = classes["BackchainDeduction"]!!.getMethod("deduce", Atom::class.java)

            // deduce(Evaluation(mortal Plato)) should chain through
            // implication rules and return [T]
            val mortalPlato = Expression(
                Symbol("Evaluation"),
                Expression(Symbol("mortal"), Symbol("Plato"))
            )
            val deduceResult = deduceMethod.invoke(null, mortalPlato) as List<*>
            assertTrue(deduceResult.isNotEmpty())
            assertTrue(deduceResult.any { it.toString() == "T" })
        }
    }

    @Test
    fun `backward chaining deduction - deduce who is mortal`() {
        compile(
            "BackchainWho.metta",
            $$"""
                ; Facts
                (Evaluation (philosopher Plato))
                (Evaluation (likes-to-wrestle Plato))

                ; Implication rules
                (Implication
                   (And (Evaluation (philosopher $x))
                        (Evaluation (likes-to-wrestle $x)))
                   (Evaluation (human $x)))

                (Implication
                   (Evaluation (human $x))
                   (Evaluation (mortal $x)))

                ; Deduction rules
                (= (deduce (Evaluation ($P $x)))
                   (match &self (Evaluation ($P $x)) T))

                (= (deduce (Evaluation ($P $x)))
                   (match &self
                     (Implication $a (Evaluation ($P $x)))
                     (deduce $a)))

                (= (deduce (And $a $b))
                   (And (deduce $a) (deduce $b)))

                (= (And T T) T)

                ; Helper to extract value when deduction succeeds
                (= (ift T $then) $then)

                ; Top-level query: who is mortal?
                ; $x should be unified with Plato during deduction

                !(ift (deduce (Evaluation (mortal $x))) $x)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach {
                println(it)
            }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("BackchainWho")

            // The top-level expression (ift (deduce (Evaluation (mortal $x))) $x)
            // should evaluate to [Plato] — $x gets unified with Plato
            // during the backward chaining deduction
            val mainResult = classes["BackchainWho"]!!.getMethod("__main").invoke(null)
            println("mainResult: $mainResult")
            val results = mainResult as List<*>
            assertTrue(results.isNotEmpty(), "Expected Plato but got empty result")
            assertTrue(
                results.any { it.toString() == "Plato" },
                "Expected Plato in results but got: $results"
            )
        }
    }

    @Test
    fun `free-variable argument reduces via space unification`() {
        compile(
            "FreeVarArgReduce.metta",
            $$"""
                ; A compiled equality-dispatch function called with a FREE-variable
                ; argument cannot bind it through the boolean ==-path, so dispatch
                ; falls to the non-reduction fallback. That fallback first tries a
                ; space `(= (f args) $r)` unification, which DOES bind the free var
                ; ($y = (air dry)) and reduces to T — only the first fact unifies.
                ; This is the b4_nondeterm deduction primitive
                ; `(prevents (making $y) (making (air wet)))`.
                (= (prevents (making (air dry)) (making (air wet))) T)
                (= (prevents (making (air wet)) (making (air dry))) T)

                !(prevents (making $y) (making (air wet)))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach { println(it) }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("FreeVarArgReduce")
            val value = classes["FreeVarArgReduce"]!!.getMethod("__main").invoke(null) as List<*>
            assertEquals(1, value.size)
            assertEquals("T", value[0].toString())
        }
    }

    @Test
    fun `cross-conjunct binding constrains the unification lookup`() {
        compile(
            "CrossBindConstrain.metta",
            $$"""
                ; The cross-conjunct binding case: $y is produced by the outer
                ; `(makes $z $y)` (→ (air wet)) and CONSUMED by the inner
                ; `(prevents (making $y) …)`. Deep variable resolution must apply
                ; the branch's $y to the inner lookup, so the lookup sees
                ; `(prevents (making (air wet)) (making (air wet)))` — which has NO
                ; rule — leaving the And inert. Without deep resolution the lookup
                ; would see $y free, wrongly re-bind it to (air dry) via the fact,
                ; and reduce to T. Mirrors b4_nondeterm's deduction conjunction.
                (= (And T T) T)
                (= (prevents (making (air dry)) (making (air wet))) T)
                (= (makes kettle (air wet)) T)
                (= (deduce $x)
                   (And (prevents (making $y) (making $x)) (makes $z $y)))

                !(deduce (air wet))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach { println(it) }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("CrossBindConstrain")
            val value = classes["CrossBindConstrain"]!!.getMethod("__main").invoke(null) as List<*>
            // The single (air wet)-binding branch fails `prevents`, so the And stays
            // inert — crucially NOT reduced to T (which is what a free, un-resolved
            // $y would have produced).
            assertEquals(1, value.size)
            assertTrue(
                value.none { it.toString() == "T" },
                "deep-resolve must keep the inconsistent branch inert, got: $value"
            )
            assertTrue(value[0].toString().contains("And"), "expected inert And, got: $value")
        }
    }

    @Test
    fun `deep deduction over And with cross-conjunct binding (b4 11 12)`() {
        compile(
            "DeepDeduction.metta",
            $$"""
                ; b4_nondeterm's deep deduction. `make` rule B's condition is an `And`
                ; over two multivalued conjuncts sharing $y: $y is produced by the left
                ; `prevents` (outer, left-to-right) and consumed by the right `makes`;
                ; $z then flows from `makes` to the result `(stop $z)`. The outer `ift`
                ; must apply per And-result with $z LIVE (compiled ift-innermost), not
                ; over a collapsed bag. The two `!` runs are independent — bindings from
                ; the first must not leak into the second.
                (= (ift T $then) $then)
                (= (And T T) T)
                (= (make $x) (ift (makes $y $x) (start $y)))
                (= (make $x) (ift (And (prevents (making $y) (making $x))
                                       (makes $z $y)) (stop $z)))
                (= (prevents (making (air dry)) (making (air wet))) T)
                (= (prevents (making (air wet)) (making (air dry))) T)
                (= (makes humidifier (air wet)) T)
                (= (makes kettle (air wet)) T)
                (= (makes ventilation (air dry)) T)
                (= (is (air dry)) (make (air wet)))
                (= (is (air wet)) (make (air dry)))

                !(assertEqual (is (air dry))
                  (superpose ((stop ventilation) (start kettle) (start humidifier))))
                !(assertEqual (is (air wet))
                  (superpose ((stop kettle) (stop humidifier) (start ventilation))))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach { println(it) }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("DeepDeduction")
            // __main runs both asserts in sequence (clearAll between them); a failed
            // assert throws, so reaching the end means both deductions reduced to the
            // expected bag.
            classes["DeepDeduction"]!!.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `free-var backward chaining binds through a multi-hop call chain (b3_direct)`() {
        compile(
            "TransitiveBackchain.metta",
            $$"""
                ; b3_direct's core: a free variable in a top-level query must reduce by
                ; backward chaining THROUGH the call chain green → frog → croaks/eat_flies,
                ; even though none of those functions is called with a free var in its own
                ; body (each passes its bound parameter down). The interprocedural relational
                ; analysis must propagate relational-ness: green is relational (top-level free
                ; $x), so its param is possibly-free, so frog is relational, so croaks/eat_flies
                ; are — and croaks/eat_flies then take the space-unification (reduceOrInert)
                ; fallback that binds $x = Fritz against `(= (croaks Fritz) T)`.
                (= (croaks Fritz) T)
                (= (eat_flies Fritz) T)
                (= (And T T) T)
                (= (frog $x) (And (croaks $x) (eat_flies $x)))
                (= (green $x) (frog $x))
                (= (ift T $then) $then)

                ; ground query still reduces via applicative compiled dispatch
                !(assertEqual (green Fritz) T)
                ; free-var query reduces by backward chaining, binding $x = Fritz
                !(assertEqual (ift (green $x) $x) Fritz)
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach { println(it) }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("TransitiveBackchain")
            // Both asserts in __main; a failure throws, so completing means both held.
            classes["TransitiveBackchain"]!!.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `multi-fact free-var backchaining foliates per-branch bindings`() {
        compile(
            "MultiFactBackchain.metta",
            $$"""
                ; e1_kb_write's core mechanism (without the &kb/new-space bits): the same
                ; free-var query now has TWO solutions (Fritz and Sam). Enumerating them
                ; exercises per-branch binding foliation: `(And (croaks $x) (eat_flies $x))`
                ; binds $x in the left conjunct, and that binding must (a) constrain the right
                ; conjunct within a branch and (b) NOT leak across sibling branches. Before
                ; foliation the branches shared one binding frame (pop's last-wins upward
                ; merge), collapsing the result to [Sam, Sam]; with foliation each branch
                ; carries its own $x, giving the correct bag {Fritz, Sam}.
                (= (croaks Fritz) T)
                (= (eat_flies Fritz) T)
                (= (croaks Sam) T)
                (= (eat_flies Sam) T)
                (= (And T T) T)
                (= (frog $x) (And (croaks $x) (eat_flies $x)))
                (= (green $x) (frog $x))
                (= (ift T $then) $then)

                !(assertEqualToResult (ift (green $x) $x) (Fritz Sam))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach { println(it) }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("MultiFactBackchain")
            classes["MultiFactBackchain"]!!.getMethod("__main").invoke(null)
        }
    }

    @Test
    fun `foliation survives a boolean and and an extra relational hop (e1_kb_write core)`() {
        compile(
            "AndFoliation.metta",
            $$"""
                ; e1_kb_write's exact shape: the conjunction is the grounded boolean `and`
                ; (not the data ctor `And`), the truth value is the symbol `True`, and `ift`
                ; is typed `(-> Bool …)` so its dispatch coerces via isTruthy. `green` adds an
                ; extra relational hop over `frog` — an identity `map?` whose Bool-typed lambda
                ; returns a raw java.lang.Boolean, a NON-Atom. foliate could not attach that
                ; branch's $x to a non-Atom, so the binding was lost and pop's last-wins merge
                ; collapsed the two solutions to [Sam, Sam]. With the non-Atom value wrapped in
                ; a Grounded before foliation, each branch keeps its own $x → {Fritz, Sam}.
                (= (croaks Fritz) True)
                (= (eat_flies Fritz) True)
                (= (croaks Sam) True)
                (= (eat_flies Sam) True)
                (= (frog $x) (and (croaks $x) (eat_flies $x)))
                (= (green $x) (frog $x))
                (: ift (-> Bool Atom Atom))
                (= (ift True $then) $then)

                !(assertEqualToResult (ift (green $x) $x) (Fritz Sam))
            """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            messageCollector.list().forEach { println(it) }
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("AndFoliation")
            classes["AndFoliation"]!!.getMethod("__main").invoke(null)
        }
    }
}