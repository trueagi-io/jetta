package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.backend.utils.toClasses
import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Symbol
import net.singularity.jetta.compiler.frontend.ir.Variable
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import net.singularity.jetta.runtime.JettaProgram
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

class PerCallBindingTest : GeneratorTestBase() {
    private val mapImpl = JvmMethod(
        owner = "net/singularity/jetta/runtime/UtilKt",
        name = "simpleMap",
        descriptor = "(Ljava/util/function/Function;Ljava/util/List;)Ljava/util/List;",
        signature = "<T:Ljava/lang/Object;R:Ljava/lang/Object;>(Ljava/util/function/Function<TT;TR;>;Ljava/util/List<+TT;>;)Ljava/util/List<TR;>;",
    )

    private val flatMapImpl = JvmMethod(
        owner = "net/singularity/jetta/runtime/UtilKt",
        name = "simpleFlatMap",
        descriptor = "(Ljava/util/function/Function;Ljava/util/List;)Ljava/util/List;",
        signature = "<T:Ljava/lang/Object;R:Ljava/lang/Object;>(Ljava/util/function/Function<TT;Ljava/util/List<TR;>;>;Ljava/util/List<+TT;>;)Ljava/util/List<TR;>;",
    )


    /**
     * Simplest per-call binding test:
     * A function receives a Variable object as an argument.
     * The match pattern uses that variable, and the match template
     * should return the bound value, not the original Variable.
     *
     *   (Color red)
     *   (Color blue)
     *   (= (lookup $x) (match &self (Color $x) $x))
     *
     * lookup(Variable("x")) should return [red, blue] — not [Variable("x"), Variable("x")]
     */
    @Test
    fun `match binds variable argument and returns bound value`() {
        compile(
            "BindVar1.metta",
            $$"""
            (Color red)
            (Color blue)
            (= (lookup $x) (match &self (Color $x) $x))
        """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("BindVar1")

            val method = classes["BindVar1"]!!.getMethod("lookup", Atom::class.java)

            // Called with a concrete symbol — should work already
            val concrete = method.invoke(null, Symbol("red")) as List<*>
            assertTrue(concrete.any { it.toString() == "red" })

            // Called with a Variable — this is the new behavior
            val withVar = method.invoke(null, Variable("x")) as List<*>
            assertTrue(withVar.isNotEmpty(), "Expected matches but got empty")
            assertTrue(
                withVar.any { it.toString() == "red" } && withVar.any { it.toString() == "blue" },
                "Expected [red, blue] but got: $withVar"
            )
        }
    }

    /**
     * Shared variable: $x appears in two arguments of a top-level call.
     * The first arg triggers a match that binds $x, and the second arg
     * should see that binding.
     *
     *   (Color red)
     *   (= (lookup $x) (match &self (Color $x) T))
     *   (= (ift T $then) $then)
     *   (ift (lookup $x) $x)
     *
     * Should return [red] — $x is bound to red by lookup's match,
     * then ift(T, red) returns red.
     */
    @Test
    fun `shared variable across top-level function arguments`() {
        compile(
            "SharedVar1.metta",
            $$"""
            (Color red)

            (= (lookup $x) (match &self (Color $x) T))
            (= (ift T $then) $then)

            (ift (lookup $x) $x)
        """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("SharedVar1")

            val mainResult = classes["SharedVar1"]!!.getMethod("__main").invoke(null)
            val results = mainResult as List<*>
            assertTrue(results.isNotEmpty(), "Expected [red] but got empty")
            assertTrue(
                results.any { it.toString() == "red" },
                "Expected red in results but got: $results"
            )
        }
    }

    /**
     * Binding propagates through a function that wraps match.
     * This is one step closer to the deduce pattern.
     *
     *   (Fact Alice)
     *   (= (check $x) (match &self (Fact $x) T))
     *   (= (ift T $then) $then)
     *   (= (who) (ift (check $x) $x))
     *
     * who() should return [Alice]
     */
    @Test
    fun `binding propagates through function wrapper`() {
        compile(
            "BindProp1.metta",
            $$"""
            (Fact Alice)

            (= (check $x) (match &self (Fact $x) T))
            (= (ift T $then) $then)
            (= (who) (ift (check $x) $x))
        """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("BindProp1")

            val method = classes["BindProp1"]!!.getMethod("who")
            val results = method.invoke(null) as List<*>
            assertTrue(results.isNotEmpty(), "Expected [Alice] but got empty")
            assertTrue(
                results.any { it.toString() == "Alice" },
                "Expected Alice in results but got: $results"
            )
        }
    }

    /**
     * Bindings from one call must NOT leak into a subsequent independent call.
     *
     *   (Color red)
     *   (Animal cat)
     *   (= (findColor $x) (match &self (Color $x) $x))
     *   (= (findAnimal $x) (match &self (Animal $x) $x))
     *
     * Calling findColor then findAnimal with fresh Variables:
     *   findColor(Variable("x")) → [red]
     *   findAnimal(Variable("x")) → [cat]   (NOT [red] leaking through)
     */
    @Test
    fun `bindings do not leak between independent calls`() {
        compile(
            "ScopedIsolation1.metta",
            $$"""
            (Color red)
            (Animal cat)
            (= (findColor $x) (match &self (Color $x) $x))
            (= (findAnimal $x) (match &self (Animal $x) $x))
        """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("ScopedIsolation1")

            val findColor = classes["ScopedIsolation1"]!!.getMethod("findColor", Atom::class.java)
            val findAnimal = classes["ScopedIsolation1"]!!.getMethod("findAnimal", Atom::class.java)

            // First call binds $x → red
            val colors = findColor.invoke(null, Variable("x")) as List<*>
            assertTrue(colors.any { it.toString() == "red" }, "Expected red but got: $colors")

            // Second call must NOT see the stale $x → red binding
            val animals = findAnimal.invoke(null, Variable("x")) as List<*>
            assertTrue(animals.isNotEmpty(), "Expected [cat] but got empty — binding likely leaked")
            assertTrue(
                animals.any { it.toString() == "cat" },
                "Expected cat but got: $animals — ${'$'}x binding from findColor leaked into findAnimal"
            )
            // Also make sure "red" did NOT sneak in
            assertTrue(
                animals.none { it.toString() == "red" },
                "red appeared in findAnimal results — binding leaked across calls"
            )
        }
    }

    /**
     * Two independent top-level expressions that each use $x.
     * The $x in the first expression must not pollute the second.
     *
     *   (Fruit apple)
     *   (City Paris)
     *   (= (findFruit $x) (match &self (Fruit $x) $x))
     *   (= (findCity $x) (match &self (City $x) $x))
     *   (= (ift T $then) $then)
     *
     *   (ift (findFruit $x) $x)   → should produce [apple]
     *   (ift (findCity $x) $x)    → should produce [Paris], not [apple]
     *
     * __main should contain results from both, with correct scoping.
     */
    @Ignore
    @Test
    fun `independent top-level expressions have isolated scopes`() {
        compile(
            "ScopedTopLevel1.metta",
            $$"""
            (Fruit apple)
            (City Paris)

            (= (findFruit $x) (match &self (Fruit $x) $x))
            (= (findCity $x) (match &self (City $x) $x))
            (= (ift T $then) $then)

            (ift (findFruit $x) $x)
            (ift (findCity $y) $y)
        """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("ScopedTopLevel1")

            val mainResult = classes["ScopedTopLevel1"]!!.getMethod("__main").invoke(null)
            val results = mainResult as List<*>

            assertTrue(
                results.any { it.toString() == "apple" },
                "Expected apple in results but got: $results"
            )
            assertTrue(
                results.any { it.toString() == "Paris" },
                "Expected Paris in results but got: $results"
            )
        }
    }

    /**
     * Nested calls: an outer function calls an inner function that binds $x.
     * After the inner call returns, the outer scope should see the
     * binding produced by the inner call — but a *sibling* inner call
     * must start with a fresh scope.
     *
     *   (Name Alice)
     *   (Name Bob)
     *   (= (resolve $x) (match &self (Name $x) $x))
     *   (= (greet $name) (Hello $name))
     *   (= (greetAll) (greet (resolve $x)))
     *
     * greetAll() should return [(Hello Alice), (Hello Bob)]
     * Each resolve call binds $x independently.
     */
    @Ignore
    @Test
    fun `nested calls produce independent bindings per match result`() {
        compile(
            "ScopedNested1.metta",
            $$"""
            (Name Alice)
            (Name Bob)

            (= (resolve $x) (match &self (Name $x) $x))
            (= (greet $name) (Hello $name))
            (= (greetAll) (greet (resolve $x)))
        """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("ScopedNested1")

            val method = classes["ScopedNested1"]!!.getMethod("greetAll")
            val results = method.invoke(null) as List<*>
            assertTrue(results.isNotEmpty(), "Expected greetings but got empty")

            val resultStrings = results.map { it.toString() }
            assertTrue(
                resultStrings.any { it.contains("Alice") },
                "Expected a greeting for Alice but got: $resultStrings"
            )
            assertTrue(
                resultStrings.any { it.contains("Bob") },
                "Expected a greeting for Bob but got: $resultStrings"
            )
        }
    }

    /**
     * Same variable name $x used in two different functions.
     * Binding $x in one function must not affect $x in another.
     *
     *   (Left L1)
     *   (Left L2)
     *   (Right R1)
     *   (= (getLeft $x) (match &self (Left $x) $x))
     *   (= (getRight $x) (match &self (Right $x) $x))
     *   (= (pair) (Pair (getLeft $x) (getRight $x)))
     *
     * pair() should produce combinations like [(Pair L1 R1), (Pair L2 R1)]
     * NOT [(Pair L1 L1)] — which would happen if getRight sees getLeft's binding.
     */
    @Ignore
    @Test
    fun `same variable name in sibling calls does not cross-pollinate`() {
        compile(
            "ScopedSibling1.metta",
            $$"""
            (Left L1)
            (Left L2)
            (Right R1)

            (= (getLeft $x) (match &self (Left $x) $x))
            (= (getRight $x) (match &self (Right $x) $x))
            (= (pair) (Pair (getLeft $x) (getRight $x)))
        """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("ScopedSibling1")

            val method = classes["ScopedSibling1"]!!.getMethod("pair")
            val results = method.invoke(null) as List<*>
            assertTrue(results.isNotEmpty(), "Expected pairs but got empty")

            val resultStrings = results.map { it.toString() }
            // getRight should find R1, not L1/L2
            assertTrue(
                resultStrings.any { it.contains("R1") },
                "Expected R1 in pairs but got: $resultStrings — getRight likely saw getLeft's binding"
            )
            // Should NOT have pairs where right side is L1 or L2
            assertTrue(
                resultStrings.none { it.matches(Regex(".*Pair.*L[12].*L[12].*")) },
                "getRight returned Left values — bindings leaked: $resultStrings"
            )
        }
    }

    /**
     * Scoped binding isolation: two functions use the same variable name $x
     * but in different definition scopes. Binding $x inside one function's
     * match must NOT leak into the other function's scope.
     *
     * In MeTTa, $x and $y in the same expression are DIFFERENT variables,
     * so (Pair (getLeft $x) (getRight $y)) should produce all combinations.
     *
     *   (Left L1)
     *   (Left L2)
     *   (Right R1)
     *   (= (getLeft $x) (match &self (Left $x) $x))
     *   (= (getRight $x) (match &self (Right $x) $x))
     *   (= (pair) (Pair (getLeft $x) (getRight $y)))
     *
     * pair() should produce combinations containing R1 on the right side.
     * $x and $y are different variables, so getRight's match is independent.
     */
    @Ignore
    @Test
    fun `scoped bindings - same variable name in sibling calls are isolated`() {
        compile(
            "ScopedSibling1.metta",
            $$"""
            (Left L1)
            (Left L2)
            (Right R1)

            (= (getLeft $x) (match &self (Left $x) $x))
            (= (getRight $x) (match &self (Right $x) $x))
            (= (pair) (Pair (getLeft $x) (getRight $y)))
        """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("ScopedSibling1")

            val method = classes["ScopedSibling1"]!!.getMethod("pair")
            val results = method.invoke(null) as List<*>
            assertTrue(results.isNotEmpty(), "Expected pairs but got empty")

            val resultStrings = results.map { it.toString() }
            // getRight must find R1 independently
            assertTrue(
                resultStrings.any { it.contains("R1") },
                "Expected R1 in pairs but got: $resultStrings — getRight likely saw getLeft's ${'$'}x binding"
            )
            // Verify no pair has Left values on both sides
            assertTrue(
                resultStrings.none { it.matches(Regex(".*Pair.*L[12].*L[12].*")) },
                "getRight returned Left values — bindings leaked across scopes: $resultStrings"
            )
        }
    }

    /**
     * Scoped binding propagation within a single scope:
     * $x appears twice in the same top-level expression — once as an
     * argument to a matching function and once as a direct reference.
     * The binding from the match must propagate to the sibling usage
     * within the SAME scope.
     *
     * This is the dual of the isolation test above: we need propagation
     * within a scope while having isolation across scopes.
     *
     *   (Name Alice)
     *   (Name Bob)
     *   (= (check $x) (match &self (Name $x) T))
     *   (= (ift T $then) $then)
     *   (= (findAll) (ift (check $x) $x))
     *
     * findAll() should return [Alice, Bob] — $x is bound by check's match
     * and the same binding is used for the second $x in ift's $then position.
     */
    @Ignore
    @Test
    fun `scoped bindings - propagation within same scope`() {
        compile(
            "ScopedPropagation1.metta",
            $$"""
            (Name Alice)
            (Name Bob)

            (= (check $x) (match &self (Name $x) T))
            (= (ift T $then) $then)
            (= (findAll) (ift (check $x) $x))
        """.trimIndent(),
            mapImpl, flatMapImpl
        ) { context ->
            registerExternals(context)
        }.let { (result, messageCollector) ->
            assertTrue(messageCollector.list().isEmpty())
            val classes = result.toMap().toClasses()
            JettaProgram.init("ScopedPropagation1")

            val method = classes["ScopedPropagation1"]!!.getMethod("findAll")
            val results = method.invoke(null) as List<*>
            assertTrue(results.isNotEmpty(), "Expected [Alice, Bob] but got empty")

            val resultStrings = results.map { it.toString() }
            assertTrue(
                resultStrings.any { it == "Alice" },
                "Expected Alice in results but got: $resultStrings"
            )
            assertTrue(
                resultStrings.any { it == "Bob" },
                "Expected Bob in results but got: $resultStrings"
            )
        }
    }
}