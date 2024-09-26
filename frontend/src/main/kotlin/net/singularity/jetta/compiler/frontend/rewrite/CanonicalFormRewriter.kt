package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.resolve.Context.SymbolDef

class CanonicalFormRewriter(
    val messageCollector: MessageCollector,
    private val resolvedFunctions: Map<String, SymbolDef>
) : Rewriter {
    private var variableCount = 0

    override fun rewrite(source: ParsedSource): ParsedSource {
        val result = mutableListOf<Atom>()
        source.code.forEach { atom ->
            val def = atom as FunctionDefinition
            val expression = def.copy(body = rewriteAtom(def.body) as Expression)
            result.add(expression)
        }
        return ParsedSource(source.filename, result)
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
    private fun rewriteExpression(expression: Expression): Atom =
        when (val f = expression.atoms.first()) {
            is Symbol -> rewriteCall(expression)
            is Special -> when (f.value) {
                Predefined.IF -> rewriteIf(expression)
                else -> expression
            }
            else -> expression
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

    private fun rewriteCall(expression: Expression): Atom {
        val argIndices = expression.getNonDeterministicArgumentsIndices()
        if (argIndices.isEmpty()) return expression

        return createMap(expression.atoms.first() as Symbol, expression.atoms.drop(1), argIndices, mutableMapOf())
    }

    private fun getArrayTypeForFunc(op: Atom, name: String, argIndex: Int): ArrowType? =
        resolvedFunctions[name]?.func?.arrowType?.types?.let { types ->
            ArrowType(types[argIndex],
                if (op == PredefinedAtoms.FLAT_MAP_) SeqType(types.last()) else types.last()
            )
        }

    private fun createMap(
        func: Symbol,
        args: List<Atom>,
        argIndices: List<Int>,
        replace: MutableMap<Int, Variable>
    ): Expression {
        // TODO: ARGS
        if (argIndices.isEmpty()) return Expression(
            func,
            *replaceArgWithLambdaVarIfNeeded(args, replace).toTypedArray()
        )
        val op = if (argIndices.size == 1) PredefinedAtoms.MAP_ else PredefinedAtoms.FLAT_MAP_
        val newVar = newVariable()
        replace[argIndices[0] - 1] = newVar
        return Expression(
            op,
            Lambda(
                listOf(newVar),
                getArrayTypeForFunc(op, func.name, argIndices[0]),
                createMap(func, args, argIndices.drop(1), replace)
            ),
            args[argIndices.first() - 1]
        )
    }

    private fun replaceArgWithLambdaVarIfNeeded(args: List<Atom>, replace: Map<Int, Variable>): List<Atom> =
        args.mapIndexed { index, atom -> replace[index] ?: atom }

    private fun newVariable(): Variable =
        Variable("__var${variableCount++}")

    private fun Expression.getNonDeterministicArgumentsIndices(): List<Int> {
        val result = mutableListOf<Int>()
        atoms.drop(1).forEachIndexed { index, atom ->
            if (atom.isNonDeterministic()) result.add(index + 1)
        }
        return result
    }

    private fun Atom.isNonDeterministic(): Boolean =
        when (this) {
            is Expression -> {
                // FIXME
                if (atoms.size == 1 && atoms[0] is Symbol) {
                    val def = resolvedFunctions[(atoms[0] as Symbol).name]
                    def != null && def.func.annotations.find { (it as? Symbol)?.name == "multivalued" } != null
                } else false
            }

            else -> false
        }

}