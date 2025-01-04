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
            val expression = def.copy(body = rewriteFunction(source.getJvmClassName(), def) as Expression)
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
            val newBody = extractIfStatementsIfNeeded(owner, functionDefinition.body, root = true)
            collectNonDeterministicAtomsRecursively(newBody, functionDefinition)
            rewriteAtom(newBody)
        } else functionDefinition.body

    private fun extractIfStatementsIfNeeded(owner: String, atom: Atom, root: Boolean): Atom {
        when (atom) {
            is Expression -> {
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
                    val result = Expression(Symbol(functionName), *params.toTypedArray())
                    result.resolved = context.resolve(functionName)
                    // return function call
                    return result
                } else {
                    return Expression(
                        atoms = atom.atoms.map { extractIfStatementsIfNeeded(owner, it, false) },
                        type = atom.type,
                        id = atom.id,
                        resolved = atom.resolved
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
                if (atom.atoms[0].isIf()) rewriteIf(atom) else rewriteExpression(atom)
            }

            else -> atom
        }

    /*
    Consider following program:

    ```
    (@ foo multivalued)
    (= (foo) (list 1 2 3))

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
                        listOf(mkVariable(replacement[0].first)),
                        getArrayTypeForFunc(op, funcName),
                        expression
                    ),
                    replacement[0].second
                ),
                op = PredefinedAtoms.FLAT_MAP_
            )
        }
        if (!expression.isNonDeterministic()) return expression

        val body = multivaluedCalls[expression.id]?.let {
            mkVariable(it)
        } ?: Expression(atoms = expression.atoms.map { atom ->
            multivaluedCalls[atom.id]?.let {
                mkVariable(it)
            } ?: rewriteAtom(atom)
        })
        // check the expression is a scope
        val replacement = multivaluedCallsInverse[expression.id]
        if (replacement != null) return createMaps(replacement, body)
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
                    rewriteAndMkSeqIfNeeded(expression, 2),//rewriteAtom(expression.atoms[2]),
                    rewriteAndMkSeqIfNeeded(expression, 3)//rewriteAtom(expression.atoms[3])
                ),
                type = expression.type,
                id = expression.id
            )
            return Expression(
                op,
                Lambda(
                    listOf(mkVariable(replacement[0].first)),
                    getArrayTypeForFunc(op, funcName),
                    newExpression
                ),
                replacement[0].second
            )
        }
        return expression
    }

    private fun collectNonDeterministicAtomsRecursively(
        atom: Atom,
        functionDefinition: FunctionLike,
        reducedScopeId: Int? = null
    ): Boolean {
        // returns closest scope according call parameters
        fun getScopeId(call: Expression): Int =
            call.atoms.drop(1)
                .mapNotNull { it as? Variable }
                .minOfOrNull { it.scope!!.id }
                ?: functionDefinition.body.id

        var isMultivalued = false
        when (atom) {
            is Expression -> {
                when (val f = atom.atoms[0]) {
                    is Symbol -> {
                        val def = context.definedFunctions[f.name]
                        if (def != null && def.func.isMultivalued()) {
                            val scopeId = reducedScopeId ?: getScopeId(atom)
                            multivaluedCalls[atom.id] = variableCount
                            multivaluedCallsInverse.getOrPut(scopeId) { mutableListOf() }.add(variableCount to atom)
                            variableCount++
                            isMultivalued = true
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
                    // other specials and symbols
                    atom.atoms.drop(1).forEach {
                        if (collectNonDeterministicAtomsRecursively(
                                it,
                                functionDefinition,
                                reducedScopeId
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
        if (atoms[0] is Symbol && context.definedFunctions[(atoms[0] as Symbol).name]!!.func.isMultivalued()) return true
        atoms.drop(1).forEach {
            if (it is Expression && it.checkIsNonDeterministicRecursively()) return true
        }
        return false
    }

    private fun getArrayTypeForFunc(op: Atom, name: String): ArrowType? =
        context.definedFunctions[name]?.func?.arrowType?.types?.let { types ->
            ArrowType(
                types.last(),
                if (op == PredefinedAtoms.FLAT_MAP_) SeqType(types.last()) else types.last()
            )
        }

    private fun mkVariable(i: Int): Variable =
        Variable("__var$i")

    private fun Atom.isNonDeterministic(): Boolean = multivaluedAtoms.contains(id)
}