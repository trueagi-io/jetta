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
) : Rewriter {
    private val typeInfo = mutableMapOf<String, Atom>()
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

    override fun rewrite(source: ParsedSource): ParsedSource {
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

    private fun mkFunctions(): List<Atom> {
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
                    position = pattern.pattern.position
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
                    position = list[0].pattern.position
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

    private fun mkArrow(expression: Expression): Atom =
        ArrowType(expression.atoms.drop(1).map {
            when (it) {
                is Expression -> mkArrow(it)
                else -> it
            }
        })

    // Head symbols that arrive as ordinary IDENTs (not dedicated operator tokens like
    // `+`/`*`) and must be promoted to their Special form. Aliases map an alternate
    // surface spelling to a canonical Predefined name — hyperon writes modulo as `%`,
    // which the lexer sees as an IDENT rather than a token.
    /** hyperon's minimal-MeTTa sequencing primitive; rewritten onto `let` (see below). */
    private val CHAIN_KEYWORD = "chain"

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
            return rewriteAtom(
                Expression(
                    Symbol(LetRewriter.LET_KEYWORD, position = func.position),
                    expression.atoms[2],
                    expression.atoms[1],
                    expression.atoms[3],
                    position = expression.position,
                )
            )
        }
        if (func is Symbol && func.name == "case" && expression.atoms.size == 3) return rewriteCaseCall(expression)
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
                func is Symbol && specials.contains(func.name) -> mkSpecialFromSymbol(it)
                else -> it
            }
        }
    }

    private fun rewriteExpressionArguments(expression: Expression): Expression =
        expression.copy(atoms = expression.atoms.map {
            rewriteAtom(it)
        })

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
                    list.add(Pattern(pattern, rewriteAtom(expression.atoms[2]), ordinal))
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
        runs.add(rewriteAtom(run.expression))
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
        const val MAIN = "__main"

        /** Compiler-internal builtin (see [net.singularity.jetta.runtime.JettaProgram] `set-watermark!`)
         *  that sets the ordered-top-level per-run visibility cutoff. */
        const val SET_WATERMARK = "set-watermark!"
    }
}