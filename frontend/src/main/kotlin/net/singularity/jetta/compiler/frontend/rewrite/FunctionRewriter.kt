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

    private fun mkFunctions(): List<Atom> =
        patterns.map { (name, list) ->
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
                enforceMultivaluedAnn(name)
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

    private fun enforceMultivaluedAnn(name: String) {
        val list = annotations.getOrPut(name) { listOf() }.toMutableList()
        list.add(PredefinedAtoms.MULTIVALUED)
        annotations[name] = list
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
        if (func is Symbol && func.name == "match") return rewriteMatchCall(expression)
        if (func is Symbol && func.name == "assertEqualToResult") return rewriteAssertionCall(expression)
        return rewriteExpressionArguments(expression).let {
            if (func is Special && func.value == Predefined.ARROW) {
                mkArrow(it)
            } else if (func is Symbol && specials.contains(func.name)) {
                mkSpecialFromSymbol(it)
            } else {
                it
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