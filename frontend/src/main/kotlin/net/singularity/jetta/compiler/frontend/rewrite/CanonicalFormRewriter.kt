package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.compiler.frontend.resolve.getJvmClassName
import net.singularity.jetta.compiler.frontend.resolve.isMultivalued

class CanonicalFormRewriter(
    val messageCollector: MessageCollector,
    private val context: Context,
) : Rewriter {
    companion object {
        // Grounded functions that consume the whole bag of results of each argument
        // (a non-determinism barrier). A bare multivalued call in their argument
        // position is passed as a List rather than lifted into a map/flat-map.
        //
        // `assertEqual`/`assertEqualToResult` are barriers: they compare the FULL bag
        // produced by each argument (multiset compare in Assertions.kt). Lifting a
        // multivalued arg into a map/flat-map would instead call the barrier once per
        // value, yielding a singleton on the actual side — see b4_nondeterm. They
        // depend on the non-reduction fallback (commit 4ebe9d0) so a non-matching call
        // like `(eq Green Blue)` reduces to itself rather than to an empty bag.
        private val BARRIER_FUNCTIONS = setOf("collapse", "assertEqual", "assertEqualToResult")
    }

    private var variableCount = 0
    private var functionCount = 0
    private val multivaluedCalls: MutableMap<Int, Int> = mutableMapOf()
    private val multivaluedCallsInverse: MutableMap<Int, MutableList<Pair<Int, Expression>>> = mutableMapOf()
    private val multivaluedAtoms: MutableSet<Int> = mutableSetOf()
    private val functions = mutableListOf<FunctionDefinition>()

    override fun rewrite(source: ParsedSource): ParsedSource {
        val result = mutableListOf<Atom>()
        source.code.map { functions.add(it as FunctionDefinition) }
        var i = 0
        while (i < functions.size) {
            val def = functions[i]
            val expression = def.copy(body = rewriteFunction(source.getJvmClassName(), def), position = def.position)
            result.add(expression)
            i++
        }

        // clean up
        multivaluedCalls.clear()
        multivaluedCallsInverse.clear()
        multivaluedAtoms.clear()
        functions.clear()

        return ParsedSource(source.filename, result)
    }

    private fun rewriteFunction(owner: String, functionDefinition: FunctionLike): Atom =
        if (functionDefinition is FunctionDefinition && functionDefinition.name != FunctionRewriter.MAIN) {
            when (val body = functionDefinition.body) {
                is Match -> {
                    Match(
                        branches = body.branches.map { branch ->
                            val newBody = extractIfStatementsIfNeeded(owner, branch.body, root = true)
                            collectNonDeterministicAtomsRecursively(newBody, functionDefinition, newBody.id)
                            val rewritten = rewriteAtom(newBody)
                            // Reset state for next branch
                            multivaluedCalls.clear()
                            multivaluedCallsInverse.clear()
                            multivaluedAtoms.clear()
                            MatchBranch(branch.cond, rewritten, branch.destructuredBindings)
                        },
                        returnType = body.returnType,
                        position = body.position
                    )
                }
                else -> {
                    val newBody = extractIfStatementsIfNeeded(owner, body, root = true)
                    // Pass newBody.id as reducedScopeId — same as the Match branch
                    // path above. Without this, when the body is a single bare
                    // multivalued call like `(= (green $x) (frog $x))`, the scope
                    // registration uses the parameter variable's scope.id (the
                    // function scope), while rewriteExpression looks up by the
                    // call's expression.id (the body itself). Those keys
                    // disagreed, so multivaluedCallsInverse came back null and
                    // the map/flat-map wrapper was never generated — the body
                    // collapsed to a free `$__varN` whose at-runtime value is a
                    // fresh Variable atom (mis-cast as List by the caller's
                    // simpleMap). Pinning the scope to the body's own id keeps
                    // both sides consulting the same bucket.
                    collectNonDeterministicAtomsRecursively(newBody, functionDefinition, newBody.id)
                    rewriteAtom(newBody)
                }
            }
        } else functionDefinition.body

    private fun extractIfStatementsIfNeeded(owner: String, atom: Atom, root: Boolean): Atom {
        when (atom) {
            is Expression -> {
                if (atom.atoms.isEmpty()) {
                    return atom
                }
                if (!root && atom.atoms[0].isIf() && atom.checkIsNonDeterministicRecursively()) {
                    val functionName = mkFunctionName(functionCount++)
                    val (params, arrowType) = extractVariables(atom)
                    val def = FunctionDefinition(
                        functionName,
                        params,
                        arrowType,
                        atom,
                        annotations = mutableListOf(PredefinedAtoms.MULTIVALUED),
                    )
                    functions.add(def)
                    context.resolveFunctionDefinition(owner, def)
                    val result = Expression(Symbol(functionName), *params.toTypedArray(), position = atom.position)
                    result.resolved = context.resolve(functionName)
                    // return function call
                    return result
                } else {
                    return Expression(
                        atoms = atom.atoms.map { extractIfStatementsIfNeeded(owner, it, false) },
                        type = atom.type,
                        id = atom.id,
                        resolved = atom.resolved,
                        position = atom.position
                    )
                }
            }

            else -> return atom
        }
    }

    private fun extractVariables(atom: Atom): Pair<List<Variable>, ArrowType> {
        fun extractVariablesRecursively(atom: Atom, variables: MutableMap<String, Variable>) {
            when (atom) {
                is Variable -> {
                    variables[atom.name] = atom
                }

                is Expression -> atom.atoms.forEach { extractVariablesRecursively(it, variables) }
                else -> {}
            }
        }

        val variables = mutableMapOf<String, Variable>()
        extractVariablesRecursively(atom, variables)
        val variablesList = variables.values.toList()
        return variablesList to ArrowType(variablesList.map { it.type!! } + atom.type!!)
    }

    private fun mkFunctionName(i: Int): String = "__f$i"

    private fun Atom.isIf(): Boolean = (this is Special && this.value == Predefined.IF)

    private fun rewriteAtom(atom: Atom): Atom =
        when (atom) {
            is Expression -> {
                if (atom.atoms.isEmpty()) atom
                else if (atom.atoms[0].isIf()) rewriteIf(atom)
                else rewriteExpression(atom)
            }

            else -> atom
        }

    /*
    Consider following program:

    ```
    (@ foo multivalued)
    (= (foo) (seq 1 2 3))

    (: f (-> Int Int))
    (= (f $x) (+ $x 1))

    (@ bar multivalued)
    (= (bar $x) (f (foo)))
    ```

    `(f (foo))` will be rewritten to
    `(map? (\ ($x1) (f $x1)) (foo))`


    in the case of
    ```
    (: f (-> Int Int Int))
    (= (f $x $y) (+ $x $y))
    ```
    `(f (foo) (foo))` will be rewritten to
    `(flat-map? (\ ($x1) -> (map? (\ ($y1) (f $x1 $y1)) (foo))) (foo))`

    but
    `(f 2 (foo))` will be rewritten to
    `(map? (\ ($x1) (f 2 $x1)) (foo))`
     */
    private fun rewriteExpression(expression: Expression): Atom {
        if (expression.atoms.isEmpty()) return expression

        fun createMaps(
            replacement: List<Pair<Int, Expression>>,
            expression: Atom,
            op: Atom = PredefinedAtoms.MAP_
        ): Atom {
            if (replacement.isEmpty()) return expression
            val funcName = (replacement[0].second.atoms[0] as Symbol).name
            return createMaps(
                replacement.drop(1), Expression(
                    op,
                    Lambda(
                        listOf(mkVariable(replacement[0].first, expression.position)),
                        getArrayTypeForFunc(op, funcName),
                        expression,
                        position = expression.position
                    ),
                    replacement[0].second,
                    position = expression.position
                ),
                op = PredefinedAtoms.FLAT_MAP_
            )
        }
        if (!expression.isNonDeterministic()) return expression

        val body = multivaluedCalls[expression.id]?.let {
            mkVariable(it, expression.position)
        } ?: Expression(atoms = expression.atoms.map { atom ->
            multivaluedCalls[atom.id]?.let {
                mkVariable(it, expression.position)
            } ?: rewriteAtom(atom)
        }, position = expression.position)
        // check the expression is a scope
        val replacement = multivaluedCallsInverse[expression.id]
        if (replacement != null) {
            // Determine the innermost op: if the body expression calls
            // a multivalued function, use FLAT_MAP_ so its List results
            // are flattened rather than nested.
            val innermostOp = if (body is Expression
                && body.atoms.isNotEmpty()
                && body.atoms[0] is Symbol
                && context.definedFunctions[(body.atoms[0] as Symbol).name]?.func?.isMultivalued() == true
            ) PredefinedAtoms.FLAT_MAP_ else PredefinedAtoms.MAP_
            return createMaps(replacement, body, innermostOp)
        }
        return body
    }

    /*
    `(if (c $x1 $x2) (b1 $x1 $x3) (b2 $x2 $x3))`
    will be transformed in two folds:
    1. Extract function
       (= (__f1 $x1 $x2 $x3)
          (flat-map? (\ ($c1) (if $c1 (b1 $x1 $x3) (b2 $x2 $x3))
       )
    2. Replace if to non-deterministic function call
     */
    private fun rewriteIf(expression: Expression): Atom {
        fun rewriteAndMkSeqIfNeeded(expression: Expression, ind: Int): Atom {
            val other = if (ind == 2) 3 else 2
            if (!expression.atoms[ind].isNonDeterministic() &&
                expression.atoms[other].isNonDeterministic()
            ) {
                val inner = rewriteAtom(expression.atoms[ind])
                return Expression(Special(Predefined.SEQ), inner, type = SeqType(inner.type!!))
            } else {
                return rewriteAtom(expression.atoms[ind])
            }
        }

        val replacement = multivaluedCallsInverse[expression.id]
        if (replacement != null) {
            val funcName = (replacement[0].second.atoms[0] as Symbol).name
            val op = if (
                expression.atoms[2].isNonDeterministic() ||
                expression.atoms[3].isNonDeterministic()
            ) PredefinedAtoms.FLAT_MAP_ else PredefinedAtoms.MAP_
            val newExpression = Expression(
                atoms = listOf(
                    expression.atoms[0],
                    rewriteAtom(expression.atoms[1]),
                    rewriteAndMkSeqIfNeeded(expression, 2),
                    rewriteAndMkSeqIfNeeded(expression, 3)
                ),
                type = expression.type,
                id = expression.id
            )
            return Expression(
                op,
                Lambda(
                    listOf(mkVariable(replacement[0].first, expression.position)),
                    getArrayTypeForFunc(op, funcName),
                    newExpression,
                    position = expression.position
                ),
                replacement[0].second
            )
        }
        return expression
    }

    private fun collectNonDeterministicAtomsRecursively(
        atom: Atom,
        functionDefinition: FunctionLike,
        reducedScopeId: Int? = null,
        // True when [atom] is a direct argument of a non-determinism barrier
        // (`assertEqual`/`assertEqualToResult`/`collapse`). Such a barrier consumes the
        // WHOLE bag of results of each argument, so a bare multivalued call here must be
        // handed over as a List rather than lifted into a map at the parent scope.
        barrierArg: Boolean = false
    ): Boolean {
        // returns closest scope according call parameters
        fun getScopeId(call: Expression): Int =
            call.atoms.drop(1)
                .mapNotNull { it as? Variable }
                .mapNotNull { it.scope?.id }
                .minOrNull()
                ?: functionDefinition.body.id

        var isMultivalued = false
        when (atom) {
            is Expression -> {
                if (atom.atoms.isEmpty()) return false

                when (val f = atom.atoms[0]) {
                    is Symbol -> {
                        // match expressions have their own variable scope —
                        // the result template is evaluated per match result,
                        // so multivalued calls inside should not be lifted out
                        if (f.name == "match") {
                            return false
                        }
                        val def = context.definedFunctions[f.name]
                        if (def != null && def.func.isMultivalued()) {
                            // Check if arguments contain multivalued sub-calls.
                            // If so, don't register this call at the parent scope —
                            // instead, let the children be scoped to this call.
                            val hasMultivaluedArgs = atom.atoms.drop(1).any { arg ->
                                arg is Expression && arg.atoms.isNotEmpty() &&
                                        arg.atoms[0] is Symbol &&
                                        context.definedFunctions[(arg.atoms[0] as Symbol).name]?.func?.isMultivalued() == true
                            }
                            // A bare multivalued call that is a direct barrier argument is
                            // handed over whole (as its List result) — skip lifting it so
                            // the barrier receives the full bag instead of being mapped
                            // once per element.
                            if (!hasMultivaluedArgs && !barrierArg) {
                                val scopeId = reducedScopeId ?: getScopeId(atom)
                                multivaluedCalls[atom.id] = variableCount
                                multivaluedCallsInverse.getOrPut(scopeId) { mutableListOf() }
                                    .add(variableCount to atom)
                                variableCount++
                                isMultivalued = true
                            }
                        }
                    }

                    else -> {}
                }

                if (atom.atoms[0].isIf()) {
                    isMultivalued = isMultivalued or collectNonDeterministicAtomsRecursively(
                        atom.atoms[1],
                        functionDefinition,
                        atom.id
                    )
                    isMultivalued = isMultivalued or collectNonDeterministicAtomsRecursively(
                        atom.atoms[2],
                        functionDefinition,
                        atom.atoms[2].id
                    )
                    isMultivalued = isMultivalued or collectNonDeterministicAtomsRecursively(
                        atom.atoms[3],
                        functionDefinition,
                        atom.atoms[3].id
                    )
                } else {
                    // When the current expression is a function call,
                    // scope any multivalued children to THIS expression
                    // so the flatMap chain wraps this call.
                    val childScope = if (atom.atoms[0] is Symbol
                        && context.definedFunctions[(atom.atoms[0] as Symbol).name] != null
                    ) atom.id else reducedScopeId

                    // Direct arguments of a barrier (assertEqual/collapse/…) are tagged
                    // so a bare multivalued call among them is handed over as a whole bag.
                    val childIsBarrierArg = (atom.atoms[0] as? Symbol)?.name in BARRIER_FUNCTIONS

                    // other specials and symbols
                    atom.atoms.drop(1).forEach {
                        if (collectNonDeterministicAtomsRecursively(
                                it,
                                functionDefinition,
                                childScope,
                                childIsBarrierArg
                            )
                        ) isMultivalued = true
                    }
                }
            }

            else -> return false
        }
        if (isMultivalued) multivaluedAtoms.add(atom.id)
        return isMultivalued
    }

    private fun Expression.checkIsNonDeterministicRecursively(): Boolean {
        if (atoms.isEmpty()) return false
        if (atoms[0] is Symbol && context.definedFunctions[(atoms[0] as Symbol).name]!!.func.isMultivalued()) return true
        atoms.drop(1).forEach {
            if (it is Expression && it.checkIsNonDeterministicRecursively()) return true
        }
        return false
    }

    private fun getArrayTypeForFunc(op: Atom, name: String): ArrowType? =
        context.definedFunctions[name]?.func?.arrowType?.types?.let { types ->
            val returnType = types.last()
            // If the function is multivalued (returns SeqType), the map/flatMap
            // lambda receives individual elements, not the whole list.
            val elementType = if (returnType is SeqType) returnType.elementType else returnType
            ArrowType(
                elementType,
                if (op == PredefinedAtoms.FLAT_MAP_) SeqType(elementType) else elementType
            )
        }

    private fun mkVariable(i: Int, position: SourcePosition?): Variable =
        Variable("__var$i", position = position)

    private fun Atom.isNonDeterministic(): Boolean = multivaluedAtoms.contains(id)
}