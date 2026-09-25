package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.ir.Match
import net.singularity.jetta.compiler.frontend.ir.MatchBranch
import net.singularity.jetta.compiler.frontend.rewrite.messages.ExpectVariableOrConstantButFoundMessage
import net.singularity.jetta.runtime.space.Space
import kotlin.math.exp

class FunctionRewriter(
    val messageCollector: MessageCollector,
    private val space: Space,
    /**
     * Optional sink that captures every plain top-level expression added to [space] for
     * the source currently being rewritten. The shared compile-time space stays the
     * resolver's input (cross-module symbol lookup needs the merged view), but the
     * collector lets the surrounding compiler driver record each source's *own* atoms
     * for per-module serialization. Null in tests / REPL where the collector isn't wired.
     */
    private val ownAtomsCollector: MutableList<Expression>? = null,
    /**
     * Whether [name] names a reducible call — a system builtin (`add-atom`, `remove-atom`,
     * …) or an already-registered external/imported function — as opposed to a data
     * constructor. User `=`-functions are tracked locally in [patterns]; this predicate
     * covers the calls the rewriter can't see there. Used by [rewriteMatchCall] to decide
     * whether a `match` template must be reduced per binding (a template that CONTAINS such
     * a call, e.g. `((add-atom …) (remove-atom …))`) rather than quoted as inert data.
     *
     * Backed by `Context.resolve` at the call sites: system functions are registered before
     * any rewriting, and imported-module functions before user sources are rewritten, so the
     * predicate is populated when it matters. Defaults to a no-op (tests/REPL that don't wire
     * it keep the pre-existing quote-the-template behaviour).
     */
    private val isReducibleName: (String) -> Boolean = { false },
    /**
     * The parameter indices at which a builtin or an already-linked function takes its argument
     * INERT (`JvmMethod.inertAtomParams`). A held meta parameter passed there is a TERM, not a
     * value, so [holdMetaParams] does not force it. Defaults to none.
     */
    private val inertParamsOf: (String) -> Set<Int> = { emptySet() },
) : Rewriter {
    private val typeInfo = mutableMapOf<String, Atom>()

    /**
     * Per function, the parameter indices its `(: f (-> …))` declaration writes LITERALLY as `Atom`
     * — hyperon's meta-type annotation, which says "hand this argument over as the TERM, do not
     * reduce it". The distinction is invisible in [typeInfo] because `asType()` erases every type it
     * does not know (`Number`, `Nat`, a user type) to `Atom` too, and reducing is right for those;
     * so it has to be read off the declaration as written. Travels to codegen via
     * `FunctionDefinition.declaredAtomParams` → `JvmMethod.templateAtomParams`, which holds the
     * argument only when it is a template — see that field for why.
     */
    private val literalAtomParams = mutableMapOf<String, Set<Int>>()

    /** As [literalAtomParams], for the meta-type `Expression`. Read only by [holdMetaParams]. */
    private val literalExpressionParams = mutableMapOf<String, Set<Int>>()

    /** Functions whose declaration writes the RESULT type literally as `Atom` — see [holdMetaParams]. */
    private val literalAtomResults = mutableSetOf<String>()
    private val annotations = mutableMapOf<String, List<Atom>>()
    private val patterns = mutableMapOf<String, MutableList<Pattern>>()
    private val runs = mutableListOf<Atom>()

    // Ordered top-level semantics (Approach 2, "space-query watermark"). Facts are added to the
    // space in source order (one [addAsFact] per top-level form), so [factCount] at the moment a
    // `!`-run is seen is exactly the count of facts declared ABOVE it. [runWatermarks] records that
    // per run (parallel to [runs]); [mkMain] emits a `set-watermark!` step before each run whose
    // watermark is short of the total, so at runtime `get-type`/`get-doc`/`typeCheckError` see only
    // the visible prefix. See `docs/specs/ordered_top_level_semantics_plan.md`.
    private var factCount = 0
    private val runWatermarks = mutableListOf<Int>()

    // `ordinal` = the rule's source position among facts (== runtime storeIndex), or -1 when no
    // `!`-run precedes it (so no reduction guard is emitted — the hot facts-then-runs shape). See
    // [mkFunctions] / MatchBranch.sourceOrdinal / `docs/specs/ordered_top_level_semantics_plan.md`.
    private data class Pattern(val pattern: Expression, val value: Atom, val ordinal: Int = -1)

    /**
     * Heads whose only `=` rules are stored by an `add-atom` — at top level or inside a function —
     * so they reach the space at run time and nothing here compiles them.
     * A call to one is wrapped in `(__reduce …)` (see `JettaProgram.__reduce`), which asks the
     * space when the call runs: before this, `!(add-atom &self (= (g $x $y) (+ $x $y)))` followed
     * by `(g 3 4)` compiled the call as the data `(g 3 4)`. A head defined at top level, or by a
     * builtin or a linked module, is excluded; empty in a file that writes no such rule, so
     * nothing else changes.
     */
    private var dynamicHeads = emptySet<String>()

    /** Heads this file defines by a top-level `=` rule — see [collectDynamicHeads]. */
    private var declaredHeads = emptySet<String>()

    /** Depth of positions whose content is DATA — a rule's text, an inert argument, a pattern. */
    private var dataDepth = 0

    override fun rewrite(source: ParsedSource): ParsedSource {
        val heads = collectDynamicHeads(source.code)
        dynamicHeads = if (isReducibleName(Predefined.REDUCE)) heads else emptySet()
        source.code.forEach {
            when (it) {
                is Expression -> rewriteTopLevelExpression(it)
                is Run -> rewriteTopLevelRun(it)
                else -> TODO()
            }
        }
        val mainPart = if (runs.isNotEmpty()) mkMain() else listOf()
        return ParsedSource(source.filename, mkFunctions() + mainPart)
    }

    private fun collectDynamicHeads(code: List<Atom>): Set<String> {
        val declared = mutableSetOf<String>()
        val nested = mutableSetOf<String>()
        fun ruleHead(e: Expression): String? =
            if ((e.atoms.firstOrNull() as? Special)?.value == Predefined.PATTERN && e.atoms.size == 3)
                ((e.atoms[1] as? Expression)?.atoms?.firstOrNull() as? Symbol)?.name
            else null
        // Only a rule an `add-atom` stores: a `(= (h …) $x)` elsewhere is a QUERY — the pattern of
        // a `match` — and `h` may well have no rule at all.
        fun walk(a: Atom) {
            if (a !is Expression) return
            if ((a.atoms.firstOrNull() as? Symbol)?.name == ADD_ATOM && a.atoms.size == 3) {
                (a.atoms[2] as? Expression)?.let { e -> ruleHead(e)?.let { nested += it } }
            }
            a.atoms.forEach { walk(it) }
        }
        code.forEach { form ->
            when (form) {
                is Expression -> {
                    val head = ruleHead(form)
                    if (head != null) { declared += head; walk(form.atoms[2]) } else walk(form)
                }
                is Run -> walk(form.expression)
                else -> {}
            }
        }
        declaredHeads = declared
        return nested.filterTo(mutableSetOf()) { it !in declared && !isReducibleName(it) }
    }

    /** Argument positions of [expression] whose content is data, not a call — see [dynamicHeads]. */
    private fun dataSlotsOf(expression: Expression): Set<Int> {
        val head = expression.atoms[0]
        if ((head as? Special)?.value == Predefined.PATTERN) return expression.atoms.indices.toSet()
        val name = (head as? Symbol)?.name ?: return emptySet()
        return when (name) {
            "let" -> setOf(1)
            "unify" -> setOf(2)
            else -> inertParamsOf(name).mapTo(mutableSetOf()) { it + 1 }
        }
    }

    private fun hasConstantsInPattern(pattern: Expression): Boolean =
        pattern.atoms.drop(1).any { it !is Variable }

    /**
     * A non-linear pattern repeats a variable across argument positions, e.g.
     * `(= (eq $x $x) T)`. Such a clause only matches when those positions are equal,
     * so it must compile through the guarded [Match] path (a `$x == $x` condition),
     * not the unconditional direct-call path — otherwise the guard is dropped and the
     * function returns its body for *any* arguments.
     */
    private fun hasRepeatedVariables(pattern: Expression): Boolean {
        val names = pattern.atoms.drop(1).filterIsInstance<Variable>().map { it.name }
        return names.size != names.toSet().size
    }

    private fun extractFormalParams(expression: Expression): List<Variable> {
        val list = expression.atoms.drop(1).mapNotNull {
            // FIXME: it might be a value
            if (it is Variable) {
                it
            } else {
                messageCollector.add(ExpectVariableOrConstantButFoundMessage(expression))
                null
            }
        }
        if (list.size != expression.atoms.size - 1) throw RewriteException(expression)
        return list
    }

    private fun mkFormalParams(pattern: Expression): List<Variable> {
        var count = 0
        return pattern.atoms.drop(1).map { Variable(mkParamName(count++)) }
    }

    private fun mkParamName(index: Int) = "var${index}"

    /**
     * Recursively collects variables from a nested pattern expression,
     * recording their extraction paths relative to the formal parameter.
     *
     * For `(And $a $b)` with paramIndex=0:
     *   $a -> DestructureBinding("a", 0, [1])
     *   $b -> DestructureBinding("b", 0, [2])
     *
     * For `(And (Pair $x $y) $b)` with paramIndex=0:
     *   $x -> DestructureBinding("x", 0, [1, 1])
     *   $y -> DestructureBinding("y", 0, [1, 2])
     *   $b -> DestructureBinding("b", 0, [2])
     */
    private fun collectNestedVariables(
        atom: Atom,
        paramIndex: Int,
        currentPath: IntArray,
        bindings: MutableList<DestructureBinding>,
        changeVariables: ChangeVariables
    ) {
        when (atom) {
            is Variable -> {
                bindings.add(DestructureBinding(atom.name, paramIndex, currentPath.copyOf()))
                val syntheticName = "destr_${paramIndex}_${currentPath.joinToString("_")}"
                changeVariables[atom.name] = syntheticName
            }
            is Expression -> {
                atom.atoms.forEachIndexed { index, child ->
                    collectNestedVariables(
                        child,
                        paramIndex,
                        currentPath + index,
                        bindings,
                        changeVariables
                    )
                }
            }
            else -> { /* Symbol, Grounded — nothing to collect */ }
        }
    }

    private fun isConstantExpression(atom: Atom): Boolean {
        when (atom) {
            is Variable -> return false
            is Expression -> {
                atom.atoms.forEach {
                    if (!isConstantExpression(it)) return false
                }
            }
            else -> return true
        }
        return true
    }

    class ChangeVariables {
        val changeVariables = mutableMapOf<String, String>()

        private fun rewriteExpression(expression: Expression): Atom =
            expression.copy(atoms = expression.atoms.map(::rewriteAtom))

        private fun rewriteLambda(lambda: Lambda): Atom =
            lambda.copy(body = rewriteAtom(lambda.body))

        fun rewriteAtom(atom: Atom): Atom =
            when (atom) {
                is Variable -> {
                    val newName = changeVariables[atom.name]
                    if (newName != null) Variable(newName) else atom
                }
                is Expression -> rewriteExpression(atom)
                is Lambda -> rewriteLambda(atom)
                else -> atom
            }

        operator fun set(name: String, newName: String) {
            changeVariables[name] = newName
        }

        fun contains(name: String): Boolean = changeVariables.containsKey(name)
    }

    private fun collectVariableNames(atom: Atom, acc: MutableSet<String>) {
        when (atom) {
            is Variable -> acc.add(atom.name)
            is Expression -> atom.atoms.forEach { collectVariableNames(it, acc) }
            else -> {}
        }
    }

    private fun substitute(
        arrowType: ArrowType?,
        pattern: Pattern,
        branchIndex: Int,
    ): Pair<Atom, List<DestructureBinding>> {
        val changeVariables = ChangeVariables()
        val destructuredBindings = mutableListOf<DestructureBinding>()

        if (isConstantExpression(pattern.value)) return pattern.value to emptyList()
        val types = mutableListOf<Atom>()
        pattern.pattern.atoms.drop(1).forEachIndexed { index, atom ->
            when (atom) {
                is Variable -> {
                    changeVariables[atom.name] = mkParamName(index)
                }
                is Expression -> {
                    collectNestedVariables(atom, index, intArrayOf(), destructuredBindings, changeVariables)
                }
                else -> { /* constant — nothing to rename */ }
            }
        }
        // Alpha-rename clause-local (body-only) variables — those NOT bound by this
        // clause's pattern/params — with a per-branch-unique suffix. Independent
        // clauses of one function routinely reuse a name (two `(= (make $x) … $y …)`
        // rules both writing `$y`); the runtime Matcher keys bindings by NAME, so a
        // binding propagated out of one clause's branch would otherwise poison a
        // sibling clause's free `$y`. Unique names give each clause-scope its own
        // variable identity — which also keeps per-branch binding snapshots
        // independent, the basis for parallel/distributed non-determinism. Pattern
        // variables keep their name (they are the call interface — incoming bindings
        // a caller passes in and reads back, e.g. `$x` in `(deduce (… $x))`).
        val bodyVars = mutableSetOf<String>()
        collectVariableNames(pattern.value, bodyVars)
        bodyVars.forEach { name ->
            if (!changeVariables.contains(name)) {
                changeVariables[name] = "${name}__c$branchIndex"
            }
        }
        if (arrowType != null) types.add(arrowType.types.last())
        return changeVariables.rewriteAtom(pattern.value) to destructuredBindings
    }

    private fun mkCond(params: List<Variable>, pattern: Expression): Expression? {
        val cond = mutableListOf<Expression>()
        if (pattern.atoms.size == 1) return null
        // Track the first param a pattern variable bound to, so a repeated variable
        // (`(eq $x $x)`) emits an equality guard between the two argument positions.
        val seenVars = mutableMapOf<String, Variable>()
        params.zip(pattern.atoms.drop(1)).forEach { (variable, atom) ->
            when (atom) {
                is Grounded<*>, is Symbol -> {
                    cond.add(Expression(Special(Predefined.COND_EQ), variable, atom, position = pattern.position))
                }
                is Expression -> {
                    cond.add(Expression(Special(Predefined.COND_EQ), variable, atom, position = pattern.position))
                }
                is Variable -> {
                    val first = seenVars[atom.name]
                    if (first != null) {
                        cond.add(Expression(Special(Predefined.COND_EQ), first, variable, position = pattern.position))
                    } else {
                        seenVars[atom.name] = variable
                    }
                }
                else -> { /* nothing to guard */ }
            }
        }
        if (cond.isEmpty()) return null
        var result: Expression = cond[0]
        cond.drop(1).forEach {
            result = Expression(Special(Predefined.AND), result, it)
        }
        return result
    }

    /**
     * The parameter positions of a `(-> T1 T2 … R)` declaration written literally as `Atom`. Only a
     * flat arrow is inspected: a nested `(-> …)` parameter is a function value, not a term to hold
     * unreduced. `%Undefined%` is deliberately NOT included — it is the gradual wildcard, and its
     * argument is an ordinary value.
     */
    private fun literalAtomIndices(declaration: Atom, metaType: String = "Atom"): Set<Int> {
        val types = arrowTypes(declaration) ?: return emptySet()
        // drop the arrow itself and the result type
        val params = types.drop(1).dropLast(1)
        // The surface spelling, as `asType()` matches it below — `GroundedType`'s own name is private.
        return params.indices.filter { (params[it] as? Symbol)?.name == metaType }.toSet()
    }

    /** Whether a `(-> …)` declaration writes its result type literally as `Atom`. */
    private fun declaresAtomResult(declaration: Atom): Boolean =
        (arrowTypes(declaration)?.takeIf { it.size >= 2 }?.last() as? Symbol)?.name == "Atom"

    private fun arrowTypes(declaration: Atom): List<Atom>? {
        val types = (declaration as? Expression)?.atoms ?: return null
        if ((types.firstOrNull() as? Symbol)?.name != Predefined.ARROW &&
            (types.firstOrNull() as? Special)?.value != Predefined.ARROW
        ) return null
        return types
    }

    /**
     * Hand a META-typed argument over as the TERM, and evaluate it where the body needs its value —
     * hyperon's semantics, at no cost to a function that does not ask for it.
     *
     * The reference passes an argument whose parameter is declared `Expression` or `Atom` without
     * evaluating it, substitutes it into the rule's body, and evaluates that body — so the term is
     * reduced exactly where it lands in a value position, and left alone where it lands in a
     * position that takes a term: an inert parameter of the callee, a `quote`, or the result of a
     * function whose own result type is `Atom` (measured on `metta-repl`: `(: q (-> Atom Atom))
     * (= (q $x) $x) !(q (+ 1 2))` answers `(+ 1 2)`, `(wu1 (+ 2 4) (+ 4 2))` over
     * `(= (wu1 $a $b) (42 $a $b))` answers `(42 6 6)`, and `(-> Expression Number)` over
     * `(+ $x 1)` answers 4). A compiled body is the same program with the substitution done by
     * the JVM, so the rewrite is local: each VALUE occurrence of a held parameter becomes
     * `(__force $x)` (see `JettaProgram.__force`), every other occurrence is left as it is, and
     * `Context` makes the parameter inert so the call site passes the term.
     *
     * Which parameters are held, per function:
     *  * `Expression` — always, when no clause destructures it. Evaluating it at the call site is
     *    not merely early, it is a type error: `(+ 4 2)` becomes a number where the descriptor
     *    wants an `Expression`, and the class does not verify.
     *  * `Atom` — only when some occurrence really is a TERM position. One used only as a value has
     *    nothing to gain but laziness, and keeps the compiled eager path at the call site and the
     *    `templateAtomParams` rule that goes with it.
     *
     * A parameter some clause destructures or repeats is matched structurally, which is the
     * existing path. One that reaches a form binding variables in a pattern (`match`, `unify`, …)
     * is left alone too: a `(__force …)` inside a pattern would be a different pattern.
     */
    private fun holdMetaParams(): Map<String, Set<Int>> {
        // Without the runtime's `__force` registered (a bare resolver in a test) nothing can
        // evaluate a held term, so nothing is held.
        if (!isReducibleName(Predefined.FORCE)) return emptyMap()
        // A held parameter of a function in THIS file is a term position for its callers, and
        // that can make one of THEIR `Atom` parameters held in turn — so decide to a fixpoint. The
        // sets only grow, and each is bounded by its declaration.
        var held = mapOf<String, Set<Int>>()
        repeat(MAX_HOLD_ROUNDS) {
            val next = patterns.keys.associateWith { heldParamsOf(it, held) }.filterValues { it.isNotEmpty() }
            if (next == held) return@repeat
            held = next
        }
        for ((name, chosen) in held) {
            val atomResult = name in literalAtomResults
            patterns[name] = patterns.getValue(name).mapTo(mutableListOf()) { clause ->
                val forced = chosen.mapTo(mutableSetOf()) { (clause.pattern.atoms[it + 1] as Variable).name }
                clause.copy(value = walkMetaUses(clause.value, true, atomResult, held) { v, isTerm ->
                    if (!isTerm && v.name in forced) Expression(Symbol(Predefined.FORCE), v) else v
                })
            }
        }
        return held
    }

    /** One round of [holdMetaParams] for [name], given what is [held] so far. */
    private fun heldParamsOf(name: String, held: Map<String, Set<Int>>): Set<Int> {
        val clauses = patterns.getValue(name)
        val expressionParams = literalExpressionParams[name].orEmpty()
        val candidates = literalAtomParams[name].orEmpty() + expressionParams
        if (candidates.isEmpty()) return emptySet()
        val atomResult = name in literalAtomResults
        val usable = candidates.filter { i ->
            clauses.all { clause ->
                val v = clause.pattern.atoms.getOrNull(i + 1) as? Variable
                v != null && clause.pattern.atoms.count { it == v } == 1
            }
        }
        if (usable.isEmpty()) return emptySet()
        val uses = MetaUses()
        clauses.forEach { clause ->
            val names = usable.associateBy { (clause.pattern.atoms[it + 1] as Variable).name }
            walkMetaUses(clause.value, true, atomResult, held, { uses.opaqueNames += it }) { v, isTerm ->
                if (isTerm) names[v.name]?.let { uses.term += it }
                v
            }
            names.forEach { (n, i) -> if (n in uses.opaqueNames) uses.opaque += i }
            uses.opaqueNames.clear()
        }
        return usable.filterTo(mutableSetOf()) { i ->
            i !in uses.opaque && (i in expressionParams || i in uses.term)
        }
    }

    private class MetaUses {
        val term = mutableSetOf<Int>()
        val opaque = mutableSetOf<Int>()
        val opaqueNames = mutableSetOf<String>()
    }

    /**
     * Visit every variable occurrence of [atom], telling [onUse] whether it sits in a TERM position
     * (`true`) or a VALUE position, and rebuild the atom from what [onUse] returns — a parameter
     * [held] by a function of this file is a term position, like a builtin's inert one. [resultPos]:
     * the occurrence is (part of) what the function returns; with [atomResult] such an occurrence
     * is a term. A form that binds pattern variables is not descended — its variables are reported
     * through [onOpaque], so the parameter can be left on the existing path.
     */
    private fun walkMetaUses(
        atom: Atom,
        resultPos: Boolean,
        atomResult: Boolean,
        held: Map<String, Set<Int>>,
        onOpaque: (String) -> Unit = {},
        onUse: (Variable, Boolean) -> Atom,
    ): Atom {
        fun walk(a: Atom, res: Boolean): Atom = walkMetaUses(a, res, atomResult, held, onOpaque, onUse)
        fun asTerm(a: Atom): Atom { varNamesIn(a).forEach { onOpaque(it) }; return a }
        return when (atom) {
            is Variable -> onUse(atom, resultPos && atomResult)
            is Expression -> {
                val atoms = atom.atoms
                val head = atoms.firstOrNull() ?: return atom
                val headName = (head as? Symbol)?.name ?: (head as? Special)?.value
                when {
                    headName == Predefined.QUOTE -> {
                        atoms.drop(1).forEach { sub -> termUses(sub, onUse) }
                        atom
                    }
                    headName == Predefined.IF && atoms.size == 4 ->
                        atom.copy(listOf(head, walk(atoms[1], false), walk(atoms[2], resultPos), walk(atoms[3], resultPos)))
                    headName == "let" && atoms.size == 4 && atoms[1] is Variable ->
                        atom.copy(listOf(head, atoms[1], walk(atoms[2], false), walk(atoms[3], resultPos)))
                    head is Variable -> atom.copy(listOf(head) + atoms.drop(1).map { walk(it, false) })
                    headName != null && (headName in PATTERN_BINDING_HEADS || headName.startsWith("match")) ->
                        asTerm(atom)
                    headName != null && head is Symbol && !(headName in patterns || isReducibleName(headName)) ->
                        // a data constructor: evaluated where it stands, element by element
                        atom.copy(atoms.map { walk(it, resultPos) })
                    head is Symbol || head is Special -> {
                        val inert = when {
                            head !is Symbol -> emptySet()
                            headName in patterns -> held[headName].orEmpty()
                            else -> inertParamsOf(headName!!)
                        }
                        atom.copy(listOf(head) + atoms.drop(1).mapIndexed { i, arg ->
                            if (i in inert) { termUses(arg, onUse); arg } else walk(arg, false)
                        })
                    }
                    // a tuple headed by a number, a string, a nested expression: data
                    else -> atom.copy(atoms.map { walk(it, resultPos) })
                }
            }
            else -> atom
        }
    }

    private fun termUses(atom: Atom, onUse: (Variable, Boolean) -> Atom) {
        when (atom) {
            is Variable -> onUse(atom, true)
            is Expression -> atom.atoms.forEach { termUses(it, onUse) }
            else -> {}
        }
    }

    private fun mkFunctions(): List<Atom> {
        val held = holdMetaParams()
        val relationalCallees = computeRelationalCallees()
        return patterns.map { (name, list) ->
            if (list.size == 1 && !hasConstantsInPattern(list[0].pattern) &&
                !hasRepeatedVariables(list[0].pattern)
            ) {
                val pattern = list[0]
                FunctionDefinition(
                    name,
                    extractFormalParams(pattern.pattern),
                    typeInfo[name] as? ArrowType,
                    pattern.value,
                    annotations[name]?.toMutableList() ?: mutableListOf(),
                    position = pattern.pattern.position,
                    declaredAtomParams = literalAtomParams[name].orEmpty(),
                    heldAtomParams = held[name].orEmpty(),
                )
            } else {
                val arrowType = typeInfo[name] as? ArrowType
                val params = mkFormalParams(list[0].pattern)
                // A multi-clause (or constant-guarded) function compiles to a Match. It is
                // STRUCTURALLY multivalued only when two clause heads can match the same
                // input (overlapping patterns) — then reducing `(f a…)` yields several
                // results. When the clauses are provably mutually exclusive, at most one
                // branch matches, so the Match is single-valued at the match level; any
                // remaining multivaluedness comes solely from the bodies (a `superpose` /
                // `match` / multivalued-callee), which MarkMultivaluedFunctionsRewriter
                // detects post-resolve and propagates. Only seed the annotation for the
                // genuinely non-deterministic (overlapping) case; an exclusive Match then
                // compiles to fast scalar dispatch (see FunctionGenerator.generateScalarMatch).
                //
                // Beyond overlap, an Atom-returning function must ALSO stay multivalued when
                // it is called RELATIONALLY — some call site passes a FREE (unbound) variable
                // argument (b2/b4 backchaining `(prevents (making $y) …)`). A free-var arg
                // cannot be bound by the compiled ==-dispatch, so reduction must fall to the
                // space-unification path (JettaCallSite.reduceOrInert) that ONLY the
                // multivalued Match provides; the scalar fallback (nonReduced) merely returns
                // the inert form. A function whose args are always ground/bound is FUNCTIONAL
                // (0-or-1 result: d, ev, lookup, a `(f X)/(f Y)` symbol mapper) and compiles
                // to scalar dispatch even when it returns an Atom. A grounded-VALUE return
                // (Int/Double/Bool/…) is always scalar-eligible regardless — an Int can never
                // be a free-var query result, so it never needs reduceOrInert. This subsumes
                // and widens the old grounded-value-only gate: it additionally frees the
                // symbolic differentiator `d`, whose recursion `(d $a)`/`(d $b)` only ever
                // passes pattern-bound (ground) arguments. See computeRelationalCallees.
                if (!clausesAreMutuallyExclusive(list) ||
                    (!returnsGroundedValue(arrowType) && name in relationalCallees)
                ) {
                    enforceMultivaluedAnn(name)
                }
                FunctionDefinition(
                    name,
                    params,
                    arrowType,
                    Match(list.mapIndexed { branchIndex, it ->
                        val (body, bindings) = substitute(arrowType, it, branchIndex)
                        MatchBranch(
                            mkCond(params, it.pattern),
                            body,
                            bindings,
                            it.ordinal
                        )
                    }, returnType = arrowType?.types?.last()),
                    annotations[name]?.toMutableList() ?: mutableListOf(),
                    position = list[0].pattern.position,
                    declaredAtomParams = literalAtomParams[name].orEmpty(),
                    heldAtomParams = held[name].orEmpty(),
                )
            }
        }
    }

    /**
     * Whole-program set of function names that are ever called RELATIONALLY — i.e. some
     * call site anywhere passes an argument that carries a FREE (unbound) variable. Such
     * a call needs the space-unification fallback ([reduceOrInert]) to bind the free var,
     * which only the multivalued Match code path emits; so a relational callee must NOT be
     * de-marked into scalar dispatch.
     *
     * Soundness by over-approximation toward "relational": the bound set at a call site is
     * just the enclosing clause's LHS pattern variables (top-level runs bind nothing), and
     * ANY free variable appearing anywhere inside an argument — even nested under a
     * reducible sub-call — counts. Both choices can only ADD names to the set, i.e. keep a
     * function multivalued. That is the safe direction: a functional fn mis-flagged
     * relational merely misses the scalar optimization, whereas a relational fn mis-flagged
     * functional would lose reduceOrInert and reduce incorrectly. A function is freed to
     * scalar only when NO call site can pass it a free var (d/ev/lookup/fib).
     */
    private fun computeRelationalCallees(): Set<String> {
        val relational = mutableSetOf<String>()

        fun walk(atom: Atom, bound: Set<String>) {
            if (atom !is Expression) return
            val head = atom.atoms.firstOrNull()
            // `let`/`let*` introduce variables that are NOT pattern parameters but ARE bound in
            // the body. Extend `bound` for the appropriate sub-walk so a callee applied to a
            // let-bound variable — `(let $e (gen $d) … (render $e) …)` — is not falsely seen as
            // relational (which would wrongly mark it @multivalued). Handles the `(quote $v)`
            // pattern LHS too (its variable is bound in the body). Runs before LetRewriter, so
            // `let`/`let*` are still literal here.
            if (head is Symbol && head.name == "let" && atom.atoms.size == 4) {
                walk(atom.atoms[2], bound)
                walk(atom.atoms[3], bound + collectVariableNames(atom.atoms[1]))
                return
            }
            if (head is Symbol && head.name == "let*" && atom.atoms.size == 3) {
                var b = bound
                (atom.atoms[1] as? Expression)?.atoms?.forEach { pair ->
                    if (pair is Expression && pair.atoms.size == 2) {
                        walk(pair.atoms[1], b)
                        b = b + collectVariableNames(pair.atoms[0])
                    }
                }
                walk(atom.atoms[2], b)
                return
            }
            (head as? Symbol)?.let { h ->
                val argsHaveFreeVar = atom.atoms.drop(1).any { arg ->
                    collectVariableNames(arg).any { it !in bound }
                }
                if (argsHaveFreeVar) relational.add(h.name)
            }
            atom.atoms.forEach { walk(it, bound) }
        }

        // Interprocedural fixpoint. A call site passing a FREE variable seeds its callee
        // relational (the base case). But relational-ness must then PROPAGATE down the call
        // chain: once F is known relational, a caller can pass a free var into any of F's
        // clause-pattern parameters, so those params are themselves possibly-free — walking
        // F's body with them treated as free flags every callee F applies to a term
        // containing one. This is what reaches `(croaks $x)` three calls below a top-level
        // `(green $x)`: green → frog → croaks, each hop widening the free set. Iterate until
        // the set stops growing (monotone, so it terminates in ≤ |functions| passes).
        //
        // Still an over-approximation toward relational (the safe direction — see the doc
        // above): a relational F's params are ALL treated as free even when some are always
        // ground, and a function is freed to scalar only when NO caller can pass any of its
        // args a free var (d/ev/lookup/fib stay scalar — never seeded).
        do {
            val before = relational.size
            patterns.forEach { (name, list) ->
                list.forEach { p ->
                    val bound =
                        if (name in relational) emptySet() else collectVariableNames(p.pattern)
                    walk(p.value, bound)
                }
            }
            runs.forEach { walk(it, emptySet()) }
        } while (relational.size > before)
        return relational
    }

    private fun enforceMultivaluedAnn(name: String) {
        val list = annotations.getOrPut(name) { listOf() }.toMutableList()
        list.add(PredefinedAtoms.MULTIVALUED)
        annotations[name] = list
    }

    /**
     * True when the function's declared return type is a grounded VALUE (Int/Double/Bool/
     * Long/String) — a computational function that can go the scalar dispatch path. Null
     * (untyped) or a symbolic/reference return (Atom, a data type) keeps the safe
     * multivalued default. Inlines the value-type set to avoid a backend dependency.
     */
    private fun returnsGroundedValue(arrowType: ArrowType?): Boolean =
        when (arrowType?.types?.lastOrNull()) {
            GroundedType.INT, GroundedType.DOUBLE, GroundedType.BOOLEAN,
            GroundedType.LONG, GroundedType.STRING -> true
            else -> false
        }

    /**
     * True when the clause heads are provably pairwise disjoint — no input can match
     * two of them, so the merged Match yields at most one result. Sound but conservative:
     * it only reports exclusivity on a *concrete* discriminant difference (distinct head
     * symbol / literal / constructor tag at some argument position); anything it cannot
     * prove disjoint (a variable/wildcard position, same-constructor patterns differing
     * only deeper) is treated as possibly-overlapping, keeping the safe multivalued
     * default. Single-clause functions are trivially exclusive.
     */
    private fun clausesAreMutuallyExclusive(list: List<Pattern>): Boolean {
        if (list.size <= 1) return true
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                if (!clausesDisjoint(list[i].pattern, list[j].pattern)) return false
            }
        }
        return true
    }

    private fun clausesDisjoint(a: Expression, b: Expression): Boolean {
        val aArgs = a.atoms.drop(1)
        val bArgs = b.atoms.drop(1)
        if (aArgs.size != bArgs.size) return true // different arity → cannot both match
        // Disjoint iff some argument position carries two concrete, differing
        // discriminants. A wildcard (variable) position never establishes disjointness.
        return aArgs.indices.any { k ->
            val da = discriminant(aArgs[k])
            val db = discriminant(bArgs[k])
            da != null && db != null && da != db
        }
    }

    /**
     * The concrete shape a pattern position matches on, or null for a wildcard (a
     * variable, which matches anything). Two positions with differing non-null
     * discriminants can never both match the same value; data-class equality gives a
     * sound, collision-free comparison.
     */
    private sealed interface Discriminant {
        data class Sym(val name: String) : Discriminant
        data class Lit(val value: Any?) : Discriminant
        data class Ctor(val head: String, val arity: Int) : Discriminant
    }

    private fun discriminant(atom: Atom): Discriminant? = when (atom) {
        is Variable -> null
        is Symbol -> Discriminant.Sym(atom.name)
        is Grounded<*> -> Discriminant.Lit(atom.value)
        is Expression -> (atom.atoms.firstOrNull() as? Symbol)?.let { Discriminant.Ctor(it.name, atom.atoms.size) }
        else -> null
    }

    private fun mkMain(): List<Atom> {
        val result = mutableListOf<Atom>()
        // Ordered top-level semantics: interleave a `set-watermark!` step before each run whose
        // visible-fact prefix is short of the total. A run seeing every fact needs no cutoff
        // (`-1` = "no filtering"), and we only emit when the cutoff CHANGES from the previous run
        // (init state is -1), so the common facts-then-runs shape emits nothing and `__main` stays
        // byte-identical to before. See `docs/specs/ordered_top_level_semantics_plan.md`.
        val total = factCount
        val mainBody = mutableListOf<Atom>()
        var lastEmitted = -1
        runs.forEachIndexed { i, run ->
            val wm = runWatermarks[i]
            val effWm = if (wm >= total) -1 else wm
            if (effWm != lastEmitted) {
                mainBody.add(Expression(listOf(Symbol(SET_WATERMARK), Grounded(effWm))))
                lastEmitted = effWm
            }
            mainBody.add(run)
        }
        result.add(
            FunctionDefinition(
                MAIN,
                listOf(),
                null,
                Expression(listOf(Special(Predefined.RUN_SEQ)) + mainBody),
                position = runs.first().position
            )
        )
        return result
    }

    private fun rewriteAtom(atom: Atom): Atom =
        when (atom) {
            is Expression -> rewriteExpression(atom)
            is Symbol -> {
                when (atom.name) {
                    Predefined.TRUE -> Grounded(true)
                    Predefined.FALSE -> Grounded(false)
                    else -> atom
                }
            }
            else -> atom
        }

    private fun mkArrow(expression: Expression): Atom {
        val components = expression.atoms.drop(1).map {
            when {
                // A nested ARROW is a function type and folds into an inner `ArrowType`. Anything
                // else parenthesised in a type position is a type APPLICATION — `($F $a)`,
                // `(Pair $a $b)`, `(List $a)` — and is left as it is, for `asType` to erase to
                // `Atom` like every other user-defined type. Reading one as an arrow (dropping its
                // head and taking the rest as the arrow's components) is what made
                // `(: fmap (-> (-> $a $b) ($F $a) ($F $b)))` promise a FUNCTION for its second
                // parameter and for its result: `($F $a)` became `(-> Atom)`, and since every
                // `ArrowType` compiles to a `JettaFunction` — an INTERFACE, which the verifier does
                // not check — the caller's `(Something 5)` travelled into that slot unchallenged
                // and the multivalued lift cast it: `Expression cannot be cast to JettaFunction`
                // inside `simpleMap`.
                it is Expression && it.isArrow() -> mkArrow(it)
                else -> it
            }
        }
        // `(->)` — an arrow with NO components — is hyperon's UNIT type, the return the reference
        // stdlib declares for its side-effecting entries (`(: add-atoms (-> SpaceType Expression
        // (->)))`, `assert`, `add-reducts`). It is not a function type: every `ArrowType` compiles
        // to a `JettaFunction` (see `ArrowType.descriptor`), so `add-atoms` promised to return one
        // and its `map?` lift then tried to cast the `()` it actually returns —
        // `ClassCastException: Expression cannot be cast to JettaFunction` inside `simpleMap`.
        //
        // ATOM rather than UNIT, following `println!`: the unit VALUE is the expression `()`, and a
        // genuinely void return breaks as soon as such a call sits in a `let` (it reaches
        // `boxIfNeeded(Unit)` and crashes the compiler).
        if (components.isEmpty()) return GroundedType.ATOM
        return ArrowType(components)
    }

    /** Whether this type-position expression is a function type `(-> …)` rather than a type
     *  application like `($F $a)`. */
    private fun Expression.isArrow(): Boolean =
        atoms.firstOrNull().let { it is Special && it.value == Predefined.ARROW }

    // Head symbols that arrive as ordinary IDENTs (not dedicated operator tokens like
    // `+`/`*`) and must be promoted to their Special form. Aliases map an alternate
    // surface spelling to a canonical Predefined name — hyperon writes modulo as `%`,
    // which the lexer sees as an IDENT rather than a token.
    /** hyperon's minimal-MeTTa sequencing primitive; rewritten onto `let` (see below). */
    private val CHAIN_KEYWORD = "chain"

    /**
     * minimal MeTTa's explicit evaluation bracket. `(function X)` reduces X one step at a time
     * until it becomes `(return $v)`, and yields that `$v`. Both halves are rewritten away — see
     * the identity rewrite below.
     */
    private val FUNCTION_KEYWORD = "function"
    private val RETURN_KEYWORD = "return"

    /**
     * minimal MeTTa's unification branch, lowered onto the `unifyMatch` runtime helper by
     * [lowerUnify] — the one primitive here that is NOT rewritten away, because a failed match
     * has to reach an else-branch.
     */
    private val UNIFY_KEYWORD = "unify"
    private val CASE_KEYWORD = "case"
    private val MATCH_KEYWORD = "match"
    private val LET_VALUE_VARIABLE = "__letValue"
    private val CASE_VARIABLE = "__case"
    private val CASE_BAG_VARIABLE = "__caseBag"
    private val UNIFY_MATCH_KEYWORD = "unifyMatch"

    /**
     * minimal MeTTa's structural-comparison branch. Same lowering as [UNIFY_KEYWORD] minus the
     * bindings — nothing is bound by an equality — so both branches take no parameters.
     */
    private val IF_EQUAL_KEYWORD = "if-equal"
    private val IF_EQUAL_MATCH_KEYWORD = "ifEqual"

    /** The JIT-eval primitive, used to run an atom a program built at run time. */
    private val EVAL_KEYWORD = "eval"

    private val specialAliases = mapOf(
        "%" to Predefined.MOD
    )

    private val specials = listOf(
        // A user-written `quote` must become the SPECIAL form, not stay a Symbol. Internally
        // generated quotes are already `PredefinedAtoms.QUOTE` (a Special) and every pass keys
        // off that; a Symbol head instead fell through to the unresolved-head path, which is the
        // DATA-CONSTRUCTOR path — and a data constructor evaluates its arguments in applicative
        // order. So `(quote (+ 1 2))` reduced to `(quote 3)`, exactly what quoting must prevent.
        Predefined.QUOTE,
        Predefined.DIV,
        Predefined.MOD,
        Predefined.NOT,
        Predefined.AND,
        Predefined.OR,
        Predefined.XOR
    ) + specialAliases.keys

    private fun quoteAtom(atom: Atom): Atom =
        Expression(PredefinedAtoms.QUOTE, atom)

    /**
     * Check if an atom is a call to a known defined function (top-level).
     */
    private fun isFunctionCall(atom: Atom): Boolean {
        if (atom !is Expression) return false
        // The empty expression `()` is ordinary data — MeTTa's nil-like value, and a routine
        // operand (`(== $list ())`). It has no head to look up.
        val head = atom.atoms.firstOrNull() ?: return false
        return head is Symbol && patterns.containsKey(head.name)
    }

    private fun rewriteMatchCall(expression: Expression): Expression {
        val template = expression.atoms[3]

        if (isFunctionCall(template)) {
            val templateExpr = template as Expression
            val funcSymbol = templateExpr.atoms[0]
            val funcArgs = templateExpr.atoms.drop(1)

            if (funcArgs.size == 1 && funcArgs[0] is Variable) {
                val lambdaVar = Variable("__matchEvalArg")
                val matchCall = Expression(
                    expression.atoms[0],
                    expression.atoms[1],
                    quoteAtom(expression.atoms[2]),
                    quoteAtom(funcArgs[0])
                )
                val lambdaBody = Expression(funcSymbol, lambdaVar)
                val lambda = Lambda(
                    listOf(lambdaVar),
                    null,
                    lambdaBody,
                    position = expression.position
                )
                return Expression(Special(Predefined.FLAT_MAP_), lambda, matchCall)
            }
        }

        // Chained `match` — the template of an outer `match` is itself a `match` call.
        // The user writes the chain expecting the inner match to be EVALUATED for each
        // result of the outer one (with the outer's bindings live), as in
        //
        //   (match &self (:= (S K K x) $r)
        //     (match &self (:= $r $r2) $r2))
        //                            ; expected: (x)
        //
        // The default `quote(template)` path would treat the inner match as data and
        // return it unreduced — `(match &self (: = (K x (K x)) $r2) $r2)`.
        //
        // Rewrite as a flat-map: the outer match yields each binding of the shared
        // variable (here `$r`), and the lambda runs the (recursively-rewritten) inner
        // match for each binding. `$r` becomes a real lambda parameter, so references
        // inside the inner pattern's `quote` see the right value at runtime.
        //
        // Restriction: exactly one variable shared between outer pattern and inner
        // template. Multi-variable chaining would need either `matchEval` (binding
        // stack via BoundAtom) or a multi-arg lambda; defer until a test needs it.
        if (isMatchCall(template)) {
            val innerMatch = template as Expression
            val outerPattern = expression.atoms[2]
            val shared = collectVariableNames(outerPattern)
                .intersect(collectVariableNames(innerMatch))

            if (shared.size == 1) {
                val sharedVar = Variable(shared.first())
                val outerCall = Expression(
                    expression.atoms[0],         // match
                    expression.atoms[1],         // &self
                    quoteAtom(outerPattern),
                    quoteAtom(sharedVar)         // dst yields the shared var's binding per match
                )
                // Recurse so chained-of-chained matches collapse correctly.
                val innerRewritten = rewriteMatchCall(innerMatch)
                val lambda = Lambda(
                    listOf(sharedVar),
                    null,
                    innerRewritten,
                    position = expression.position
                )
                return Expression(Special(Predefined.FLAT_MAP_), lambda, outerCall)
            }
        }

        // Compound template containing a NESTED function call, e.g. `explain` clause 2:
        //   (match &self (Implication $a (Evaluation ($P $x))) (($P $x) proven by (explain $a)))
        // The whole-template single-call path above only fires when the template IS the
        // call. Here the call `(explain $a)` is buried inside a data tuple. Quoting the
        // whole template (the default below) makes the nested call inert data that never
        // reduces — `(explain X)` then returns the SHALLOW `(… proven by (explain …))`.
        //
        // Lift the call explicitly, mirroring the late non-determinism hoisting:
        //   flat-map? (\ $a. map? (\ $r. (($P $x) proven by $r))  (explain $a))
        //             (match &self <pattern> (quote $a))
        // i.e. the outer match drives `$a` (the single match-bound variable the call
        // depends on); the inner `map?` evaluates `(explain $a)` as a REAL call and splices
        // each result `$r` back into the data tuple. Remaining template variables resolve as
        // before (function params / destructured locals captured into the lambdas).
        //
        // Restrictions (else fall through to quote — no behaviour change): exactly ONE
        // nested call, depending on exactly ONE match-bound variable. Multiple calls or
        // drive-vars would need multi-arg lambdas / tuple-returning match (same deferral as
        // the chained-match path above).
        if (template is Expression) {
            val nestedCalls = collectNestedFunctionCalls(template)
            if (nestedCalls.size == 1) {
                val call = nestedCalls[0]
                val callName = (call.atoms[0] as? Symbol)?.name
                val matchVars = collectVariableNames(expression.atoms[2])
                val callVars = collectVariableNames(call).intersect(matchVars)
                // Only lift MULTIVALUED nested calls. The lift wraps the call in
                // map?/flat-map?, which require it to return a List; a single-valued call
                // returns a scalar (→ ClassCast at runtime). For single-valued nested
                // calls fall through to quote — the same shallow behaviour as before this
                // change, so no regression.
                if (callName != null && callVars.size == 1 && isMultivaluedFunction(callName)) {
                    val driveVar = Variable(callVars.first())
                    val resultVar = Variable("__matchEvalRes")
                    val splicedTemplate = replaceSubExpression(template, call, resultVar)
                    val matchCall = Expression(
                        expression.atoms[0],
                        expression.atoms[1],
                        quoteAtom(expression.atoms[2]),
                        quoteAtom(driveVar)
                    )
                    val innerLambda = Lambda(
                        listOf(resultVar),
                        null,
                        splicedTemplate,
                        position = expression.position
                    )
                    val inner = Expression(Special(Predefined.MAP_), innerLambda, call)
                    val outerLambda = Lambda(
                        listOf(driveVar),
                        null,
                        inner,
                        position = expression.position
                    )
                    return Expression(Special(Predefined.FLAT_MAP_), outerLambda, matchCall)
                }
            }
        }

        // A template whose nested CALLS are written over the match's OWN pattern variables, and
        // which the single-call lift above declined — c3's Implication rule,
        //
        //   (match &self (.tv (Implication $y $x) (stv $s $c))
        //     (stv (* $s (s-tv (TV $y))) (* $c (c-tv (TV $y)))))
        //
        // four calls over three pattern variables. No single lift drives that, and the general
        // `__matchTmpl` path below cannot either: it compiles the template as a lambda, and a
        // pattern variable is CAPTURED into that lambda when it is created — before the match has
        // run. So `(TV $y)` was compiled with `$y` unbound and answered with every `.tv` fact in
        // the space, while `$s` and `$c` were substituted only at the very end, into a term whose
        // arithmetic had long since been frozen as data: `(stv (* 0.8 (s-tv <the whole store>)) …)`.
        //
        // Substituting first and reducing after turns it into ordinary ground evaluation, which is
        // what `matchReduceTemplate` does — one applicative walk per match, arguments before
        // heads. A data constructor has neither a rule nor a registry entry, so the parts of the
        // template that are data stay exactly as written.
        //
        // Narrow deliberately: only USER function calls count (`isFunctionCall` reads `patterns`),
        // so a template built of system calls — `(add-atom &self (foo $x))`, the `hide` idiom —
        // keeps the lambda path below, where their own runtime `resolveDeep` substitutes the
        // bindings.
        if (template is Expression) {
            val nestedCalls = collectNestedFunctionCalls(template)
            val matchVars = collectVariableNames(expression.atoms[2])
            val dependsOnMatchVars = nestedCalls.any { call ->
                collectVariableNames(call).any { it in matchVars }
            }
            if (dependsOnMatchVars) {
                return expression.copy(
                    listOf(
                        Symbol("matchReduceTemplate"),
                        expression.atoms[1],
                        quoteAtom(expression.atoms[2]),
                        quoteAtom(template)
                    )
                )
            }
        }

        // General case: the template CONTAINS a reducible call (a system builtin such as
        // add-atom/remove-atom, a user function, or an imported one) but is not one of the
        // specific single-call shapes handled above — e.g. a tuple of side-effecting calls
        // `((add-atom …) (remove-atom …))`, the body of the `hide` idiom, or a lone system
        // call `(add-atom …)` (the whole-template case above only catches USER calls, which
        // are the only ones in `patterns`). Quoting it (the default below) would leave those
        // calls inert. Instead evaluate the template once per match binding: the outer match
        // installs every pattern-variable binding (its BoundAtom snapshot flows into the
        // Matcher via flat-map?'s unwrap), and the lambda body — the template — reduces with
        // its free variables resolved from those bindings. Reducible sub-calls run (see
        // FunctionGenerator's evalCalls path); genuine data stays inert. The lambda parameter
        // is a throwaway: the pattern vars flow through the binding stack, not the argument.
        if (templateHasReducibleCall(template)) {
            val throwaway = Variable("__matchTmpl")
            val matchCall = expression.copy(
                listOf(
                    expression.atoms[0],
                    expression.atoms[1],
                    quoteAtom(expression.atoms[2]),
                    quoteAtom(throwaway)
                )
            )
            val lambda = Lambda(
                listOf(throwaway),
                null,
                rewriteAtom(template),
                position = expression.position
            )
            // map?, not flat-map?: the lambda reduces the template to ONE value per binding
            // (the substituted template — for `hide` a discarded unit tuple), so results are
            // collected 1:1 with matches. flat-map? would require the lambda to return a List
            // to flatten and ClassCasts on the scalar template value.
            return Expression(Special(Predefined.MAP_), lambda, matchCall)
        }

        // Grounded-operator template, e.g. `(match &kb (, …) (- $y $x))`. `match` substitutes
        // the bindings into the template — `(- 1.5 0.7)` — but returns it inert; hyperon
        // evaluates it (→ 0.8). Route to `matchReduce`, which runs each substituted result
        // through the runtime grounded-op reducer. Quoting is identical to the inert fall-
        // through below: the `quote` is a compile-time "treat as data" marker, so at runtime
        // matchReduce's inner `match` still substitutes into `(- $y $x)` before reducing.
        if (templateIsGroundedOp(template)) {
            return expression.copy(
                listOf(
                    Symbol("matchReduce"),
                    expression.atoms[1],
                    quoteAtom(expression.atoms[2]),
                    quoteAtom(template)
                )
            )
        }

        // A RULE-BODY query — `(match &m (= (f 2) $x) $x)`, pattern `(= <lhs> $x)` with the same
        // variable as the whole template. What `$x` binds to is a rule body, and hyperon does not
        // hand it back as data: the enclosing form evaluates it, so `(if (< 2 0) (- 0 2)
        // (g (+ 1 2)))` is seen as `(g 3)`. JeTTa's match returns an inert Atom and nothing
        // downstream reduces it, so route to `matchReduceDeep`, which runs each result through
        // the full evaluator. Deliberately narrow: only this pattern shape, so a `match` over
        // ordinary DATA facts keeps returning its bindings verbatim.
        if (templateIsRuleBodyVariable(expression.atoms[2], template)) {
            return expression.copy(
                listOf(
                    Symbol("matchReduceDeep"),
                    expression.atoms[1],
                    quoteAtom(expression.atoms[2]),
                    quoteAtom(template)
                )
            )
        }

        return expression.copy(
            listOf(
                expression.atoms[0],
                expression.atoms[1],
                quoteAtom(expression.atoms[2]),
                quoteAtom(expression.atoms[3])
            )
        )
    }

    /** Grounded binary operators whose surface/Predefined spellings a match template may use. */
    private val groundedOpNames = setOf(
        "+", "-", "*", "/", "div", "%", "mod", "<", ">", "<=", ">=", "==",
    )

    /**
     * Is [atom] a grounded-operator application `(op a b)` — head a Special (`+`/`-`/…) or a
     * grounded-op Symbol (`div`/`mod`)? Such a match template must be evaluated after binding
     * substitution (routed to `matchReduce`), unlike inert data templates. Special-headed
     * arithmetic is invisible to [templateHasReducibleCall] (which only sees Symbol heads), so
     * this is the sole path that reduces them.
     */
    /**
     * Is this a rule-BODY query — pattern `(= <lhs> $x)` whose template is that same `$x`? Then
     * the match answers with a rule body, which the enclosing form evaluates in hyperon. Both
     * spellings of the `=` head are accepted (a [Special] after canonicalisation, a [Symbol] as
     * written).
     */
    private fun templateIsRuleBodyVariable(pattern: Atom, template: Atom): Boolean {
        if (template !is Variable) return false
        if (pattern !is Expression || pattern.atoms.size != 3) return false
        val head = when (val h = pattern.atoms[0]) {
            is Special -> h.value
            is Symbol -> h.name
            else -> return false
        }
        if (head != Predefined.PATTERN) return false
        return (pattern.atoms[2] as? Variable)?.name == template.name
    }

    private fun templateIsGroundedOp(atom: Atom): Boolean {
        if (atom !is Expression || atom.atoms.size != 3) return false
        val op = when (val h = atom.atoms[0]) {
            is Special -> h.value
            is Symbol -> h.name
            else -> return false
        }
        return op in groundedOpNames
    }

    /**
     * Does [atom] contain, anywhere, a reducible call — an Expression whose head Symbol is a
     * user function ([patterns]) or a system/imported function ([isReducibleName])? A nested
     * `match` is excluded (the chained-match path handles it); Special-headed forms
     * (arithmetic/if/…) are naturally excluded since their head is not a Symbol. Drives
     * [rewriteMatchCall]'s decision to reduce the template per binding rather than quote it.
     */
    private fun templateHasReducibleCall(atom: Atom): Boolean = when (atom) {
        is Expression -> {
            val headName = (atom.atoms.firstOrNull() as? Symbol)?.name
            val headIsReducible = headName != null && headName != "match" &&
                (patterns.containsKey(headName) || isReducibleName(headName))
            headIsReducible || atom.atoms.any { templateHasReducibleCall(it) }
        }

        else -> false
    }

    /**
     * Collect function-call sub-expressions (head is a Symbol naming a known defined
     * function) anywhere inside [atom]. Used by [rewriteMatchCall] to detect calls
     * nested inside a compound match template.
     */
    private fun collectNestedFunctionCalls(atom: Atom): List<Expression> {
        val result = mutableListOf<Expression>()
        fun walk(a: Atom) {
            if (a is Expression) {
                if (isFunctionCall(a)) result.add(a)
                a.atoms.forEach(::walk)
            }
        }
        walk(atom)
        return result
    }

    /**
     * Structurally replace every occurrence of [target] inside [atom] with [replacement].
     * Used by [rewriteMatchCall] to splice a nested call's fresh result variable back into
     * the surrounding template. ([Expression.equals] is structural.)
     */
    private fun replaceSubExpression(atom: Atom, target: Expression, replacement: Atom): Atom =
        when {
            atom == target -> replacement
            atom is Expression -> atom.copy(atom.atoms.map { replaceSubExpression(it, target, replacement) })
            else -> atom
        }

    /**
     * Whether [name] is a multivalued (List-returning) function, mirroring the
     * single-clause/linear test in [mkFunctions]: a function is single-valued only if it
     * has exactly one clause with no constants and no repeated variables in its pattern.
     */
    private fun isMultivaluedFunction(name: String): Boolean {
        val list = patterns[name] ?: return false
        return !(list.size == 1 &&
            !hasConstantsInPattern(list[0].pattern) &&
            !hasRepeatedVariables(list[0].pattern))
    }

    private fun isMatchCall(atom: Atom): Boolean {
        if (atom !is Expression) return false
        val head = atom.atoms.firstOrNull() ?: return false
        return head is Symbol && head.name == "match"
    }

    private fun collectVariableNames(atom: Atom): Set<String> {
        val result = mutableSetOf<String>()
        fun walk(a: Atom) {
            when (a) {
                is Variable -> result.add(a.name)
                is Expression -> a.atoms.forEach(::walk)
                else -> {}
            }
        }
        walk(atom)
        return result
    }

    /**
     * `(case S ((P1 B1) (P2 B2) …))` — evaluate the scrutinee once and return the body of the
     * first matching pattern. Desugars to a `let` (binding the scrutinee so it is not
     * re-evaluated) over a right-nested `if` chain:
     *   - a literal / symbol pattern Pi becomes `(if (== $case Pi) Bi rest)`;
     *   - a variable pattern binds and short-circuits — `(let Pi $case Bi)` — so it is the
     *     catch-all default and anything after it is unreachable (as in hyperon);
     *   - the `Empty` pattern (matches only a no-result scrutinee) is not handled by this
     *     scalar chain yet — it is skipped, leaving the remaining chain.
     * The chain's final fallthrough is the empty tuple `()`. Structural patterns (a
     * constructor with sub-patterns) rely on `==` and only match when structurally equal;
     * full unification is a later step.
     */
    private fun rewriteCaseCall(expression: Expression): Atom {
        val scrutinee = rewriteAtom(expression.atoms[1])
        val clausesExpr = expression.atoms[2] as? Expression ?: return expression
        val clauses = clausesExpr.atoms.filterIsInstance<Expression>().filter { it.atoms.size == 2 }
        val caseVar = Variable("__case")
        var acc: Atom = Expression(emptyList())
        for (clause in clauses.asReversed()) {
            val pat = clause.atoms[0]
            val body = rewriteAtom(clause.atoms[1])
            acc = when {
                pat is Variable ->
                    Expression(Symbol(LetRewriter.LET_KEYWORD), pat, caseVar, body)

                pat is Symbol && pat.name == "Empty" -> acc

                else -> Expression(
                    Special(Predefined.IF),
                    Expression(Special(Predefined.COND_EQ), caseVar, pat),
                    body,
                    acc
                )
            }
        }
        return Expression(Symbol(LetRewriter.LET_KEYWORD), caseVar, scrutinee, acc)
    }

    /**
     * Lower minimal MeTTa's `(unify $a $b $then $else)` onto the runtime [unifyMatch] helper —
     * `(unifyMatch (names…) $a $b (\ (names…) $then) (\ () $else))`.
     *
     * `unify` is a four-way form: the two atoms are unified SYMMETRICALLY (the reference stdlib
     * writes the pattern on either side — `(unify $list ($head $tail) …)` and
     * `(unify ($head $tail) $ht …)` both occur), and only the taken branch is evaluated, hence
     * two lambdas rather than two arguments.
     *
     * The whole difficulty is the then-branch's PARAMETER LIST: the variables the unification
     * binds. Codegen already draws the line for the atoms themselves — inside an inert argument
     * `generateQuote` loads an in-scope variable's VALUE and emits a genuinely free one as
     * `Variable` data — so a pattern built from bound values (hyperon's `if-decons-expr`, whose
     * `$head`/`$tail` are meta-type `Variable` PARAMETERS) needs nothing special. What codegen
     * cannot decide is which names the branch body must receive positionally: exactly the ones
     * NOT already in scope, since an in-scope name must stay a capture of the enclosing slot.
     * Listing it as a parameter instead would shadow that slot with an unbound `Variable`.
     *
     * Scope is therefore threaded explicitly here, top-down, before the ordinary rewrite: an
     * over-approximating pre-pass cannot work, because a `unify`'s own pattern variables are
     * binders for its then-branch while being ordinary in-scope values for a `unify` NESTED in
     * that branch — which is precisely how the reference `let*` is written.
     *
     * DIVERGENCE, deliberate: the then-branch is a compiled lambda, so it is EVALUATED with the
     * bindings but not SUBSTITUTED into. That is invisible unless the branch is a runtime-built
     * template that shares variable names with the unified pair (hyperon's `switch-internal`
     * passes `$template` through). Substituting into a template and then evaluating it is the
     * runtime-compilation path, not this one.
     */
    private fun lowerUnify(expression: Expression, scope: Set<String>): Atom {
        val a = expression.atoms[1]
        val b = expression.atoms[2]
        val thenBranch = expression.atoms[3]
        val elseBranch = expression.atoms[4]
        val bound = (varNamesIn(a) + varNamesIn(b)).distinct().filter { it !in scope }
        val inner = scope + bound
        val params = Expression(bound.map { Variable(it) }, position = expression.position)
        return Expression(
            listOf(
                Symbol(UNIFY_MATCH_KEYWORD, position = expression.position),
                // The parameter NAMES, as Symbols: the runtime helper needs them to pass the
                // bindings positionally, and a quoted Symbol never collapses to a slot the way a
                // quoted Variable would. See JettaProgram.unifyMatch.
                Expression(bound.map { Symbol(it) }, position = expression.position),
                lowerUnifyForms(a, scope),
                lowerUnifyForms(b, scope),
                Expression(
                    listOf(Special(Predefined.LAMBDA), params, lowerUnifyForms(thenBranch, inner)),
                    position = thenBranch.position,
                ),
                Expression(
                    listOf(
                        Special(Predefined.LAMBDA),
                        Expression(emptyList(), position = elseBranch.position),
                        lowerUnifyForms(elseBranch, scope),
                    ),
                    position = elseBranch.position,
                ),
            ),
            position = expression.position,
        )
    }

    /**
     * Lower `(if-equal $a $b $then $else)` onto the runtime [ifEqual] helper. [lowerUnify]'s
     * sibling: the same two-branch shape, but an equality binds nothing, so both branches are
     * parameterless and no scope analysis is needed — only the recursion into them.
     */
    private fun lowerIfEqual(expression: Expression, scope: Set<String>): Atom {
        fun branch(atom: Atom): Atom = Expression(
            listOf(
                Special(Predefined.LAMBDA),
                Expression(emptyList(), position = atom.position),
                lowerUnifyForms(atom, scope),
            ),
            position = atom.position,
        )
        return Expression(
            listOf(
                Symbol(IF_EQUAL_MATCH_KEYWORD, position = expression.position),
                lowerUnifyForms(expression.atoms[1], scope),
                lowerUnifyForms(expression.atoms[2], scope),
                branch(expression.atoms[3]),
                branch(expression.atoms[4]),
            ),
            position = expression.position,
        )
    }

    /**
     * `(case VAL ((PAT BODY) …))` as the reference defines it: every result of VAL is matched
     * against the patterns by UNIFICATION, the first clause that unifies wins, and a result no
     * clause unifies with yields nothing. An `Empty` clause fires when VAL has no result at all.
     *
     *     (let $__case VAL (unify $__case PAT1 BODY1 (unify $__case PAT2 BODY2 (empty))))
     *
     * and, with an `(Empty E)` clause, VAL's results are collected first so that their absence
     * can be seen:
     *
     *     (let $__bag (collapse VAL) (unify $__bag () E (let $__case (superpose $__bag) …)))
     *
     * The old lowering compared with `==` (so a pattern with variables, or a Bool read back as
     * data, never matched), dropped the `Empty` clause, and answered `()` when nothing matched —
     * a value where the reference has none (caseempty, ifcasenondet).
     */
    private fun desugarCase(expression: Expression): Atom? {
        val clausesExpr = expression.atoms[2] as? Expression ?: return null
        val clauses = clausesExpr.atoms.map { it as? Expression ?: return null }
        if (clauses.any { it.atoms.size != 2 }) return null
        val position = expression.position
        fun sym(name: String) = Symbol(name, position = position)
        fun expr(vararg atoms: Atom) = Expression(atoms.toList(), position = position)
        val caseVar = Variable(CASE_VARIABLE, position = position)
        val isEmptyClause = { c: Expression -> (c.atoms[0] as? Symbol)?.name == "Empty" }
        var chain: Atom = expr(sym("empty"))
        for (clause in clauses.filterNot(isEmptyClause).asReversed()) {
            chain = expr(sym(UNIFY_KEYWORD), caseVar, clause.atoms[0], clause.atoms[1], chain)
        }
        val emptyClause = clauses.firstOrNull(isEmptyClause)
            ?: return expr(sym(LetRewriter.LET_KEYWORD), caseVar, expression.atoms[1], chain)
        val bag = Variable(CASE_BAG_VARIABLE, position = position)
        // The emptiness test is a `unify` against `()` rather than an `if`: its branches are
        // lambdas, so the `superpose` over the bag is lifted INSIDE the else-branch. Under an `if`
        // the lift hoists it above the test, and an empty bag then maps over nothing at all.
        return expr(
            sym(LetRewriter.LET_KEYWORD), bag, expr(sym("collapse"), expression.atoms[1]),
            expr(
                sym(UNIFY_KEYWORD), bag, Expression(emptyList(), position = position),
                emptyClause.atoms[1],
                expr(sym(LetRewriter.LET_KEYWORD), caseVar, expr(sym("superpose"), bag), chain),
            ),
        )
    }

    /**
     * A `match` whose template BRANCHES — holds an `if`, a `unify` or a `case` — evaluated the way
     * the reference evaluates it: once per match, with the pattern's variables bound to that
     * match's values.
     *
     *     (match S P T)  →  (let ($v1 … $vn) (match S P ($v1 … $vn)) T)
     *
     * The match answers plain data (the tuple of its variables' values, the fast path), and the
     * pattern-`let` — `letMatch`, applied per result — makes those variables PARAMETERS of the
     * lambda that holds T. The template paths of [rewriteMatch] cannot do this for a branching form: a template
     * compiled as a lambda captures the pattern variables before the match has run, so
     * `(match &self (p $x $y) (unify $y 1 one other))` unified a free `$y` and said `one` for every
     * fact, and an `if` template came back as unevaluated data.
     *
     * Only variables not already in [scope] become parameters: one bound around the `match` is a
     * value the pattern is matched against, and stays a capture of the enclosing slot.
     */
    private fun evaluateTemplateOverBindings(expression: Expression, scope: Set<String>): Atom {
        val position = expression.position
        val names = varNamesIn(expression.atoms[2]).filter { it !in scope }
        fun tuple() = Expression(names.map { Variable(it, position = position) }, position = position)
        return Expression(
            listOf(
                Symbol(LetRewriter.LET_KEYWORD, position = position),
                tuple(),
                expression.copy(atoms = listOf(expression.atoms[0], expression.atoms[1], expression.atoms[2], tuple())),
                expression.atoms[3],
            ),
            position = position,
        )
    }

    /** Whether [atom] holds a form whose branch depends on a VALUE: `if`, `unify`, `case`. */
    private fun containsBranchingForm(atom: Atom): Boolean {
        if (atom !is Expression || atom.atoms.isEmpty()) return false
        val head = atom.atoms[0]
        if (head == PredefinedAtoms.QUOTE || (head as? Symbol)?.name == Predefined.QUOTE) return false
        // The parser already makes `if` a `Special`; `unify`/`case` are still Symbols here.
        val name = (head as? Symbol)?.name ?: (head as? Special)?.value
        if ((name == Predefined.IF && atom.atoms.size == 4) ||
            (name == UNIFY_KEYWORD && atom.atoms.size == 5) ||
            (name == CASE_KEYWORD && atom.atoms.size == 3)
        ) return true
        return atom.atoms.any { containsBranchingForm(it) }
    }

    /**
     * A `let` left-hand side that is a STRUCTURE to unify, not a variable to bind: any expression
     * but the `(quote $v)` form, which `LetRewriter` lowers onto its quote peeler.
     */
    private fun isStructuralPattern(lhs: Atom): Boolean {
        if (lhs !is Expression) return false
        val head = lhs.atoms.firstOrNull()
        val isQuote = head == PredefinedAtoms.QUOTE || (head as? Symbol)?.name == Predefined.QUOTE ||
            (head as? Special)?.value == Predefined.QUOTE
        return !(isQuote && lhs.atoms.size == 2 && lhs.atoms[1] is Variable)
    }

    /** Variable names in [atom], in document order. */
    private fun varNamesIn(atom: Atom): List<String> {
        val out = mutableListOf<String>()
        fun go(a: Atom) {
            when (a) {
                is Variable -> out.add(a.name)
                is Expression -> a.atoms.forEach(::go)
                else -> {}
            }
        }
        go(atom)
        return out.distinct()
    }

    /**
     * Rewrite every `unify` form in [atom] with [scope] carrying the variable names bound around
     * it. Binder forms (`let`, `let*`, `chain`, a lambda, and `unify`'s own then-branch) extend
     * the scope for their body only; `quote`d data is left untouched.
     */
    private fun lowerUnifyForms(atom: Atom, scope: Set<String>): Atom {
        if (atom !is Expression || atom.atoms.isEmpty()) return atom
        val head = atom.atoms[0]
        val name = (head as? Symbol)?.name
        if (head == PredefinedAtoms.QUOTE || name == Predefined.QUOTE) return atom
        if (name == UNIFY_KEYWORD && atom.atoms.size == 5) return lowerUnify(atom, scope)
        if (name == CASE_KEYWORD && atom.atoms.size == 3) {
            desugarCase(atom)?.let { return lowerUnifyForms(it, scope) }
        }
        if (name == IF_EQUAL_KEYWORD && atom.atoms.size == 5) return lowerIfEqual(atom, scope)
        if (name == LetRewriter.LET_KEYWORD && atom.atoms.size == 4 && isStructuralPattern(atom.atoms[1])) {
            // A pattern-`let` IS a `unify`, as the reference defines it:
            // `(= (let $p $a $t) (unify $a $p $t Empty))`. Lowered through `unify` it binds the
            // variables of BOTH sides — `(let ($a $b 3) (1 2 $c) …)` gives `$c` = 3 (corpus
            // `letlet`) — which the `letMatch` route could not, because only this pass knows which
            // names are in scope. A failed match yields nothing.
            //
            // The VALUE is evaluated first, through an ordinary variable `let`: the reference's
            // `let` takes it as `%Undefined%` (reduced) and only then hands it to `unify`, whose
            // `Atom` parameters are not reduced at all.
            val position = atom.position
            val value = Variable(LET_VALUE_VARIABLE, position = position)
            return lowerUnifyForms(
                Expression(
                    listOf(
                        Symbol(LetRewriter.LET_KEYWORD, position = position), value, atom.atoms[2],
                        Expression(
                            listOf(
                                Symbol(UNIFY_KEYWORD, position = position), value, atom.atoms[1], atom.atoms[3],
                                Expression(listOf(Symbol("empty", position = position)), position = position),
                            ),
                            position = position,
                        ),
                    ),
                    position = position,
                ),
                scope,
            )
        }
        if (name == LetRewriter.LET_KEYWORD && atom.atoms.size == 4) {
            return atom.copy(
                atoms = listOf(
                    head,
                    atom.atoms[1],
                    lowerUnifyForms(atom.atoms[2], scope),
                    lowerUnifyForms(atom.atoms[3], scope + varNamesIn(atom.atoms[1])),
                )
            )
        }
        // `(match SPACE PATTERN TEMPLATE)` binds PATTERN's variables in TEMPLATE: a `unify` (or a
        // `case`) in the template must see them as values, not as fresh binders of its own — else
        // `(match &self (p $x $y) (unify $y 1 one other))` unified a free `$y` and always said `one`.
        if (name == MATCH_KEYWORD && atom.atoms.size == 4) {
            if (containsBranchingForm(atom.atoms[3])) {
                return lowerUnifyForms(evaluateTemplateOverBindings(atom, scope), scope)
            }
            return atom.copy(
                atoms = listOf(
                    head,
                    lowerUnifyForms(atom.atoms[1], scope),
                    atom.atoms[2],
                    lowerUnifyForms(atom.atoms[3], scope + varNamesIn(atom.atoms[2])),
                )
            )
        }
        // `(chain X $v T)` binds `$v` in T only. Handled here as written, before the rewrite onto
        // `let` further down, so a `unify` in either position sees the right scope.
        if (name == CHAIN_KEYWORD && atom.atoms.size == 4) {
            return atom.copy(
                atoms = listOf(
                    head,
                    lowerUnifyForms(atom.atoms[1], scope),
                    atom.atoms[2],
                    lowerUnifyForms(atom.atoms[3], scope + varNamesIn(atom.atoms[2])),
                )
            )
        }
        if (name == LetRewriter.LETSTAR_KEYWORD && atom.atoms.size == 3) {
            val bindings = atom.atoms[1] as? Expression ?: return atom
            // `let*` is nested `let`s — desugared HERE, so each pair takes the `let` branch above
            // with the scope its predecessors built, and a STRUCTURAL pair lowers through
            // `unify` like any pattern-`let` (corpus `letlet`: `(let* ((($f1 $c1 3) (1 2 $d1))) …)`).
            if (bindings.atoms.any { it !is Expression || it.atoms.size != 2 }) return atom
            val nested = bindings.atoms.foldRight(atom.atoms[2]) { pair, body ->
                pair as Expression
                Expression(
                    listOf(Symbol(LetRewriter.LET_KEYWORD, position = atom.position), pair.atoms[0], pair.atoms[1], body),
                    position = atom.position,
                )
            }
            return lowerUnifyForms(nested, scope)
        }
        if (head is Special && head.value == Predefined.LAMBDA && atom.atoms.size == 3) {
            return atom.copy(
                atoms = listOf(
                    head,
                    atom.atoms[1],
                    lowerUnifyForms(atom.atoms[2], scope + varNamesIn(atom.atoms[1])),
                )
            )
        }
        return atom.copy(atoms = atom.atoms.map { lowerUnifyForms(it, scope) })
    }

    private fun rewriteAssertionCall(expression: Expression): Expression {
        if (expression.atoms.size != 3) return expression
        return expression.copy(
            atoms = listOf(
                expression.atoms[0],
                rewriteAtom(expression.atoms[1]),
                quoteAtom(expression.atoms[2])
            )
        )
    }

    private fun rewriteExpression(expression: Expression): Atom {
        if (expression.atoms.isEmpty()) return expression
        val func = expression.atoms[0]
        // `quote` is inert data — never rewrite its contents. Otherwise a quoted
        // `(match …)`/function call inside `(eval '(…))` would be match-rewritten here
        // (pattern/template wrapped in quote), then rewritten AGAIN when JIT-eval
        // re-runs the pipeline → double-quoted pattern that matches nothing. The quoted
        // form must reach eval exactly as the user wrote it.
        // Match the NAME, not just the Special: a `quote` written in the source is still a Symbol
        // at this point (it becomes a Special further down, in `mkSpecialFromSymbol`), so keying
        // on `PredefinedAtoms.QUOTE` alone let a source-written quote's contents be rewritten.
        // The head is normalised to the Special HERE, because returning early skips the conversion
        // below — and a Symbol head would leave the form on codegen's data-constructor path, which
        // evaluates its arguments (the very thing quoting must prevent).
        if (func == PredefinedAtoms.QUOTE || (func is Symbol && func.name == Predefined.QUOTE)) {
            return expression.copy(atoms = listOf(PredefinedAtoms.QUOTE) + expression.atoms.drop(1))
        }
        if (func is Symbol && func.name == "match") return rewriteMatchCall(expression)
        // `chain` is `let` with the binding evaluated: `(chain X $v T)` binds $v to the value of X
        // and yields T. Rewriting it onto `let` reuses the whole tested binding path instead of
        // adding a second one. DIVERGENCE: hyperon's `chain` takes ONE reduction step, where `let`
        // evaluates fully. Same answer whenever the bound expression reaches a value in one step
        // (every use in the reference `stdlib.metta` and in the corpus), different for a program
        // that relies on stepwise control — which is the point of `chain` in minimal MeTTa.
        if (func is Symbol && func.name == CHAIN_KEYWORD && expression.atoms.size == 4) {
            // A bare VARIABLE in the stepped position is the one shape where `let` is not enough.
            // `chain` evaluates its first argument after substitution, so a variable there means
            // "run the program this variable holds" — the reference `map-atom` builds a template
            // with `atom-subst` and then does `(chain $map-expr $head-mapped …)` to run it. A `let`
            // would bind the template ITSELF, unevaluated. Routing it through `eval` is the
            // runtime-compilation path, which is exactly what evaluating a runtime-built atom is.
            val stepped = expression.atoms[1]
            val value = if (stepped is Variable)
                Expression(Symbol(EVAL_KEYWORD, position = stepped.position), stepped, position = stepped.position)
            else stepped
            return rewriteAtom(
                Expression(
                    Symbol(LetRewriter.LET_KEYWORD, position = func.position),
                    expression.atoms[2],
                    value,
                    expression.atoms[3],
                    position = expression.position,
                )
            )
        }
        // `function`/`return` are minimal MeTTa's evaluation bracket: `(function X)` reduces X one
        // step at a time until it becomes `(return $v)`, then yields `$v`. The bracket exists to
        // express WHEN to stop stepping — and JeTTa does not step: wherever it evaluates, it
        // evaluates to a value. So both halves carry no information here and become the identity,
        // leaving the `chain`s inside them as the `let`s they already rewrite to. This is what
        // turns the reference `stdlib.metta`'s bodies from data (`function` was an unknown head,
        // so the whole body compiled as a constructor) into ordinary compiled code.
        //
        // Same trade as `chain`, and the same divergence: identical answers whenever the body
        // reaches its `return` — every use in that file — and different for a program that uses
        // the bracket for stepwise control, which is the point of it in minimal MeTTa. As with
        // `chain`, a user-defined function of the same name is shadowed by this rewrite; hyperon
        // reserves both names too.
        if (func is Symbol && expression.atoms.size == 2 &&
            (func.name == FUNCTION_KEYWORD || func.name == RETURN_KEYWORD)
        ) {
            return rewriteAtom(expression.atoms[1])
        }
        if (func is Symbol && func.name == "case" && expression.atoms.size == 3) return rewriteCaseCall(expression)
        if (isReducibleName(Predefined.SUPERPOSE_VALUE)) unionSuperpose(expression)?.let { return rewriteAtom(it) }
        if (func is Symbol && func.name == "assertEqualToResult") return rewriteAssertionCall(expression)
        return rewriteExpressionArguments(expression).let {
            when {
                func is Special && func.value == Predefined.ARROW -> mkArrow(it)
                // 2-arg `(if cond then)` — hyperon sugar for "then when cond, else Empty".
                // Pad with an empty-tuple else so every downstream `if` site (resolver,
                // CanonicalFormRewriter.rewriteIf, generateIf) can assume the 3-arg shape
                // and needn't special-case arity (they destructure `atoms[3]` directly).
                func is Special && func.value == Predefined.IF && it.atoms.size == 3 ->
                    it.copy(atoms = it.atoms + Expression(emptyList()))
                func is Symbol && specials.contains(func.name) && !isOutOfShapeAsSpecial(it) ->
                    mkSpecialFromSymbol(it)
                func is Symbol && dataDepth == 0 && func.name in dynamicHeads ->
                    Expression(Symbol(Predefined.REDUCE, position = func.position), it, position = it.position)
                else -> it
            }
        }
    }

    /**
     * `(superpose (e1 … en))` over a literal tuple holding a CALL is the UNION of what each element
     * evaluates to: the reference hands back the elements and evaluates each one, so
     * `(superpose ((wu1) (wu2)))` over an empty `(wu1)` answers `(wu2)`'s value alone. Compiled as
     * written, the tuple was a data constructor over its elements, and a multivalued element was
     * LIFTED — the whole tuple once per result, a product where the reference takes a union (and
     * an empty element emptied everything). Rewritten to
     * `(__superpose (union-atom (collapse e1) (union-atom … (collapse en))))`: each `collapse`
     * takes its element's bag whole. A tuple of data only (`(a b c)`, `((a b) (c d))`) is left alone.
     */
    private fun unionSuperpose(expression: Expression): Expression? {
        val head = expression.atoms[0] as? Symbol ?: return null
        if (head.name != SUPERPOSE || expression.atoms.size != 2) return null
        val tuple = expression.atoms[1] as? Expression ?: return null
        // `(superpose (f …))` superposes what the CALL answers — a tuple only once it has run.
        if (tuple.atoms.isEmpty() || isCall(tuple) || !tuple.atoms.any { isCall(it) }) return null
        val pos = expression.position
        val parts = tuple.atoms.map { Expression(Symbol(COLLAPSE, position = pos), it, position = pos) }
        val union = parts.dropLast(1).foldRight(parts.last() as Atom) { part, acc ->
            Expression(Symbol(UNION_ATOM, position = pos), part, acc, position = pos)
        }
        // `__superpose`, not `superpose`: an Expression argument of `superpose` is the literal
        // tuple (the reference does not evaluate it), so `(superpose (union-atom …))` would
        // superpose `union-atom` and its operands.
        return Expression(Symbol(Predefined.SUPERPOSE_VALUE, position = head.position), union, position = pos)
    }

    /** A call to a function of this file, a builtin or a linked one — not a data tuple. */
    private fun isCall(atom: Atom): Boolean {
        val name = ((atom as? Expression)?.atoms?.firstOrNull() as? Symbol)?.name ?: return false
        return name in declaredHeads || isReducibleName(name)
    }

    private fun rewriteExpressionArguments(expression: Expression): Expression {
        val data = if (dynamicHeads.isEmpty()) emptySet() else dataSlotsOf(expression)
        return expression.copy(atoms = expression.atoms.mapIndexed { i, arg ->
            if (i in data) {
                dataDepth++
                try { rewriteAtom(arg) } finally { dataDepth-- }
            } else rewriteAtom(arg)
        })
    }

    /**
     * Whether turning this head into a `Special` would produce a form the grounded operator
     * cannot serve. `div`, `mod`, `and`, … are WORDS, so a program may legitimately define its
     * own function of the same name at a different arity — hyperon's `he_minimalmetta.metta`
     * defines `(= (div $x $y $accum) …)` on top of the grounded `div/2` and calls it.
     *
     * Made a `Special` regardless, such a call was `isMisappliedSpecial` from then on: the
     * resolver stamped it inert data before ever looking for a user function, so `(div 10 5 0)`
     * never reduced and nothing diagnosed it. Left a `Symbol`, it resolves — or stays inert
     * exactly as any other unresolved application does.
     */
    private fun isOutOfShapeAsSpecial(expression: Expression): Boolean =
        mkSpecialFromSymbol(expression).isMisappliedSpecial()

    private fun mkSpecialFromSymbol(expression: Expression): Expression {
        val atoms = expression.atoms.mapIndexed { index, atom ->
            if (index == 0) {
                val name = (atom as Symbol).name
                Special(specialAliases[name] ?: name)
            } else {
                atom
            }
        }
        return expression.copy(atoms = atoms)
    }

    private fun rewriteTopLevelExpression(expression: Expression) {
        when ((expression.atoms[0] as? Special)?.value) {
            Predefined.PATTERN -> {
                val pattern = expression.atoms[1] as? Expression
                val head = pattern?.atoms?.getOrNull(0) as? Symbol
                if (head != null) {
                    val list = patterns.getOrPut(head.name) { mutableListOf() }
                    // Guard this clause's reduction by source position ONLY if a `!`-run precedes
                    // it (else -1 = always-visible, no guard). `factCount` here == the storeIndex
                    // this fact will get (addAsFact ++s it just below), matching the run watermark.
                    val ordinal = if (runs.isNotEmpty()) factCount else -1
                    // `unify` is lowered in its own top-down pass first: it is the one form whose
                    // rewrite depends on which variables are already in scope, and the clause head
                    // is what seeds that scope. See [lowerUnify].
                    val body = lowerUnifyForms(expression.atoms[2], varNamesIn(pattern).toSet())
                    list.add(Pattern(pattern, rewriteAtom(body), ordinal))
                }
                // Every `(= lhs rhs)` is ALSO an equality fact in the space, whether or
                // not its head compiles to a JVM function. This is the reference
                // interpreter's "rules live as space atoms" model: it lets runtime
                // `match &self (= …)` queries — and the eval-as-runtime dispatcher
                // (JettaCallSite) — find the rule by unification, including for
                // free-variable arguments the compiled boolean path can't bind. The
                // compiled function and the space fact coexist (curried / meta-rule
                // heads with `head == null` get only the space fact, as before).
                addAsFact(expression)
            }

            Predefined.TYPE -> {
                val symbol = expression.atoms[1] as? Symbol
                if (symbol != null) {
                    // Record which parameters were written LITERALLY as `Atom` before `asType()`
                    // erases every unknown type to the same thing — see [literalAtomParams].
                    literalAtomParams[symbol.name] = literalAtomIndices(expression.atoms[2])
                    literalExpressionParams[symbol.name] = literalAtomIndices(expression.atoms[2], "Expression")
                    if (declaresAtomResult(expression.atoms[2])) literalAtomResults += symbol.name
                    typeInfo[symbol.name] = rewriteAtom(expression.atoms[2]).asType()
                    // ALSO keep the type as a space fact so it is visible at runtime
                    // (`get-doc` / future `get-type` query `&self`). This is additive: the
                    // compile-time `typeInfo` inference above is unchanged. The RAW expression
                    // is stored, so the arrow stays a readable `(-> …)` Expression rather than
                    // the type-erased ATOM `rewriteAtom` would produce.
                    addAsFact(expression)
                } else {
                    // Type for a non-Symbol form, e.g. `(: (A B) PairAB)`. Keep it in
                    // the space as a typed fact; the resolver's per-symbol typeInfo
                    // doesn't apply.
                    addAsFact(expression)
                }
            }

            Predefined.ANNOTATION -> {
                val symbol = expression.atoms[1] as? Symbol
                if (symbol != null) {
                    annotations[symbol.name] = expression.atoms.drop(2)
                    // ALSO reach the space so `get-doc` can query documentation at runtime
                    // (the compile-time `annotations` map keys by tag and is never read back).
                    addAsFact(expression)
                } else {
                    addAsFact(expression)
                }
            }

            else -> addAsFact(expression)
        }
    }

    private fun addAsFact(expression: Expression) {
        space.add(expression)
        ownAtomsCollector?.add(expression)
        factCount++
    }

    private fun rewriteTopLevelRun(run: Run) {
        // Snapshot the facts-declared-above count BEFORE rewriting the run body (rewriting never
        // adds facts, but keep the read at the source-order point for clarity).
        runWatermarks.add(factCount)
        // A run has no enclosing clause, so every variable in a `unify` it contains is free.
        runs.add(rewriteAtom(lowerUnifyForms(run.expression, emptySet())))
    }

    /**
     * Lower a type-position atom to a [GroundedType] / [ArrowType]. JeTTa currently
     * type-erases everything that isn't a known ground type to [GroundedType.ATOM] so
     * MeTTa's user-defined types (`Either`, `Pair $a $b`, `Type`, `%Undefined%`, tvars
     * `$t`) all compile to `Object` on the JVM. This loses the symbolic type for any
     * downstream type checking, but it lets programs that rely on user/parametric
     * types reach codegen — proper type-aware analysis is a future improvement.
     */
    private fun Atom.asType(): Atom =
        when (this) {
            is Symbol -> when (name) {
                "Int" -> GroundedType.INT
                "Long" -> GroundedType.LONG
                "Double" -> GroundedType.DOUBLE
                "Boolean", "Bool" -> GroundedType.BOOLEAN
                "String" -> GroundedType.STRING
                "Unit" -> GroundedType.UNIT
                "Atom" -> GroundedType.ATOM
                "Any" -> GroundedType.ANY
                "Expression" -> GroundedType.EXPRESSION
                "List" -> GroundedType.LIST
                "Nothing" -> GroundedType.NOTHING
                "Space" -> GroundedType.SPACE
                else -> GroundedType.ATOM
            }
            is ArrowType -> ArrowType(types = types.map { it.asType() })
            is Variable -> GroundedType.ATOM
            is Expression -> GroundedType.ATOM
            else -> GroundedType.ATOM
        }

    companion object {
        private const val MAX_HOLD_ROUNDS = 8

        /**
         * Forms (as [rewriteAtom] leaves them) that bind variables in a pattern of their own; a held
         * parameter inside one is not rewritten — see [holdMetaParams]. `match…` is matched by prefix.
         */
        private val PATTERN_BINDING_HEADS = setOf(
            "let", "let*", "unify", "unifyMatch", "letMatch", "case", "chain", "sealed", "atom-subst",
            Predefined.LAMBDA,
        )

        const val MAIN = "__main"

        private const val ADD_ATOM = "add-atom"
        private const val SUPERPOSE = "superpose"
        private const val COLLAPSE = "collapse"
        private const val UNION_ATOM = "union-atom"

        /** Compiler-internal builtin (see [net.singularity.jetta.runtime.JettaProgram] `set-watermark!`)
         *  that sets the ordered-top-level per-run visibility cutoff. */
        const val SET_WATERMARK = "set-watermark!"
    }
}