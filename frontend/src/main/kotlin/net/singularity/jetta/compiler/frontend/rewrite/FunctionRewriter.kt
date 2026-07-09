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

    private data class Pattern(val pattern: Expression, val value: Atom)

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
                            bindings
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
            (atom.atoms.firstOrNull() as? Symbol)?.let { head ->
                val argsHaveFreeVar = atom.atoms.drop(1).any { arg ->
                    collectVariableNames(arg).any { it !in bound }
                }
                if (argsHaveFreeVar) relational.add(head.name)
            }
            atom.atoms.forEach { walk(it, bound) }
        }

        patterns.forEach { (_, list) ->
            list.forEach { p -> walk(p.value, collectVariableNames(p.pattern)) }
        }
        runs.forEach { walk(it, emptySet()) }
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
        val mainBody = runs
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

    private val specials = listOf(
        Predefined.DIV,
        Predefined.MOD,
        Predefined.NOT,
        Predefined.AND,
        Predefined.OR,
        Predefined.XOR
    )

    private fun quoteAtom(atom: Atom): Atom =
        Expression(PredefinedAtoms.QUOTE, atom)

    /**
     * Check if an atom is a call to a known defined function (top-level).
     */
    private fun isFunctionCall(atom: Atom): Boolean {
        if (atom !is Expression) return false
        val head = atom.atoms[0]
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

        return expression.copy(
            listOf(
                expression.atoms[0],
                expression.atoms[1],
                quoteAtom(expression.atoms[2]),
                quoteAtom(expression.atoms[3])
            )
        )
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
        if (func == PredefinedAtoms.QUOTE) return expression
        if (func is Symbol && func.name == "match") return rewriteMatchCall(expression)
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
                Special((atom as Symbol).name)
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
                    list.add(Pattern(pattern, rewriteAtom(expression.atoms[2])))
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
    }

    private fun rewriteTopLevelRun(run: Run) {
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
    }
}