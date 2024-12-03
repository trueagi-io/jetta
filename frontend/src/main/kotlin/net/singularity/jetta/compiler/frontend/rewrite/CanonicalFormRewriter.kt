package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.Context.SymbolDef
import net.singularity.jetta.compiler.frontend.resolve.isMultivalued

class CanonicalFormRewriter(
    val messageCollector: MessageCollector,
    private val resolvedFunctions: Map<String, SymbolDef>
) : Rewriter {
    private var variableCount = 0
    private val multivaluedCalls: MutableMap<Int, Int> = mutableMapOf()
    private val multivaluedCallsInverse: MutableMap<Int, MutableList<Pair<Int, Expression>>> = mutableMapOf()
    private val multivaluedAtoms: MutableSet<Int> = mutableSetOf()

    override fun rewrite(source: ParsedSource): ParsedSource {
        val result = mutableListOf<Atom>()
        source.code.forEach { atom ->
            val def = atom as FunctionDefinition
            val expression = def.copy(body = rewriteFunction(def) as Expression)
            result.add(expression)
        }

        // clean up
        multivaluedCalls.clear()
        multivaluedCallsInverse.clear()
        multivaluedAtoms.clear()

        return ParsedSource(source.filename, result)
    }

    private fun rewriteFunction(functionDefinition: FunctionLike): Atom {
        collectNonDeterministicAtomsRecursively(functionDefinition.body, functionDefinition)
        return rewriteAtom(functionDefinition.body)
    }

    private fun rewriteAtom(atom: Atom): Atom =
        when (atom) {
            is Expression -> rewriteExpression(atom)
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
        fun createMaps(replacement: List<Pair<Int, Expression>>, expression: Expression, op: Atom = PredefinedAtoms.MAP_): Atom {
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
        val body = Expression(atoms = expression.atoms.map { atom ->
            multivaluedCalls[atom.id]?.let { mkVariable(it) } ?: rewriteAtom(atom)
        })
        // check the expression is a scope
        val replacement = multivaluedCallsInverse[expression.id]
        if (replacement != null) return createMaps(replacement, body)
        return body
    }


    /*
    1. (if (>= (foo) 2) 0 1)
    2. (if (map?..) 0 1)
    3. set multivalued flag to the expression
    4. pull out map?
     */
    private fun rewriteIf(expression: Expression): Atom {
        return expression
    }

    private fun collectNonDeterministicAtomsRecursively(atom: Atom, functionDefinition: FunctionLike): Boolean {
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
                        val def = resolvedFunctions[f.name]
                        if (def != null && def.func.isMultivalued()) {
                            val scopeId = getScopeId(atom)
                            multivaluedCalls[atom.id] = variableCount
                            multivaluedCallsInverse.getOrPut(scopeId) { mutableListOf() }.add(variableCount to atom)
                            variableCount++
                            isMultivalued = true
                        }
                    }

                    else -> {}
                }

                atom.atoms.drop(1).forEach {
                    if (collectNonDeterministicAtomsRecursively(it, functionDefinition)) isMultivalued = true
                }
            }

            else -> return false
        }
        if (isMultivalued) multivaluedAtoms.add(atom.id)
        return isMultivalued
    }

    private fun getArrayTypeForFunc(op: Atom, name: String): ArrowType? =
        resolvedFunctions[name]?.func?.arrowType?.types?.let { types ->
            ArrowType(
                types.last(),
                if (op == PredefinedAtoms.FLAT_MAP_) SeqType(types.last()) else types.last()
            )
        }

    private fun mkVariable(i: Int): Variable =
        Variable("__var$i")

    private fun Atom.isNonDeterministic(): Boolean = multivaluedAtoms.contains(id)
}