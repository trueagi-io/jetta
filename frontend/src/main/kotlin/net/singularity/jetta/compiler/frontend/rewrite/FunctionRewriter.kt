package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.ir.Match
import net.singularity.jetta.compiler.frontend.ir.MatchBranch
import net.singularity.jetta.compiler.frontend.rewrite.messages.ExpectVariableOrConstantButFoundMessage
import kotlin.math.exp

class FunctionRewriter(val messageCollector: MessageCollector) : Rewriter {
    private val typeInfo = mutableMapOf<String, Atom>()
    private val annotations = mutableMapOf<String, List<Atom>>()
    private val patterns = mutableMapOf<String, MutableList<Pattern>>()
    private val main = mutableListOf<Atom>()

    private data class Pattern(val pattern: Expression, val value: Atom)

    override fun rewrite(source: ParsedSource): ParsedSource {
        source.code.forEach {
            when (it) {
                is Expression -> rewriteTopLevelExpression(it)
                else -> TODO()
            }
        }
        val mainPart = if (main.isNotEmpty()) mkMain() else listOf()
        return ParsedSource(source.filename, mkFunctions() + mainPart)
    }

    private fun hasConstantsInPattern(pattern: Expression): Boolean =
        pattern.atoms.drop(1).any { it !is Variable }

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
                // Give each destructured variable a unique name based on param index and path
                // e.g., $a from param 0 path [1] becomes $destr_0_1
                //        $b from param 0 path [2] becomes $destr_0_2
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
    }

    private fun substitute(arrowType: ArrowType?, pattern: Pattern): Pair<Atom, List<DestructureBinding>> {
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
                    // Nested pattern — collect variables with extraction paths
                    collectNestedVariables(atom, index, intArrayOf(), destructuredBindings, changeVariables)
                }
                else -> { /* constant — nothing to rename */ }
            }
        }
        if (arrowType != null) types.add(arrowType.types.last())
        return changeVariables.rewriteAtom(pattern.value) to destructuredBindings
    }

    private fun mkCond(params: List<Variable>, pattern: Expression): Expression? {
        val cond = mutableListOf<Expression>()
        if (pattern.atoms.size == 1) return null
        params.zip(pattern.atoms.drop(1)).forEach { (variable, atom) ->
            when (atom) {
                is Grounded<*>, is Symbol -> {
                    cond.add(Expression(Special(Predefined.COND_EQ), variable, atom))
                }
                is Expression -> {
                    // For nested patterns like (And $a $b), generate structural match conditions.
                    // Compare the formal param against the full pattern expression
                    // (the condition uses == which the Match evaluator handles structurally).
                    cond.add(Expression(Special(Predefined.COND_EQ), variable, atom))
                }
                else -> { /* Variable — no condition needed */ }
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
            if (list.size == 1 && !hasConstantsInPattern(list[0].pattern)) {
                val pattern = list[0]
                FunctionDefinition(
                    name,
                    extractFormalParams(pattern.pattern),
                    typeInfo[name] as? ArrowType,
                    pattern.value,
                    annotations[name]?.toMutableList() ?: mutableListOf()
                )
            } else {
                val arrowType = typeInfo[name] as? ArrowType
                val params = mkFormalParams(list[0].pattern)
                enforceMultivaluedAnn(name)
                FunctionDefinition(
                    name,
                    params,
                    arrowType,
                    Match(list.map {
                        val (body, bindings) = substitute(arrowType, it)
                        MatchBranch(
                            mkCond(params, it.pattern),
                            body,
                            bindings
                        )
                    }, returnType = arrowType?.types?.last()),
                    annotations[name]?.toMutableList() ?: mutableListOf()
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
        // FIXME: turn it back
//        var count = 0
//
//        val calls = main.map {
//            val fnName = "__main_${count++}"
//            result.add(
//                FunctionDefinition(
//                    fnName,
//                    listOf(),
//                    null,
//                    it
//                )
//            )
//            Expression(Symbol(fnName))
//        }
//        result.add(
//            FunctionDefinition(
//                MAIN,
//                listOf(),
//                null,
//                Expression(listOf(Special(Predefined.RUN_SEQ)) + calls)
//            )
//        )
        val fnName = MAIN
        val main1 = main.map { rewriteAtom(it) }
        result.add(
            FunctionDefinition(
                fnName,
                listOf(),
                null,
                Expression(listOf(Special(Predefined.RUN_SEQ)) + main1)
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

    private fun rewriteMatchCall(expression: Expression): Expression =
        expression.copy(
            listOf(
                expression.atoms[0],
                expression.atoms[1],
                quoteAtom(expression.atoms[2]),
                quoteAtom(expression.atoms[3])
            )
        )


    private fun rewriteExpression(expression: Expression): Atom {
        val func = expression.atoms[0]
        if (func is Symbol && func.name == "match") return rewriteMatchCall(expression)
        return rewriteExpressionArguments(expression).let {
            if (func is Special && func.value == Predefined.ARROW) {
                mkArrow(it)
            } else if (func is Symbol && specials.contains(func.name)) {
                mkSpecialFromSymbol(it)
            } else {
                expression
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
                val pattern = expression.atoms[1] as Expression
                val symbol = pattern.atoms[0] as Symbol
                val list = patterns.getOrPut(symbol.name) { mutableListOf() }
                // FIXME: it's better to separate rewrite rules
                list.add(Pattern(pattern, rewriteAtom(expression.atoms[2])))
            }

            Predefined.TYPE -> {
                val symbol = expression.atoms[1] as Symbol
                typeInfo[symbol.name] = rewriteAtom(expression.atoms[2]).asType()
            }

            Predefined.ANNOTATION -> {
                val symbol = expression.atoms[1] as Symbol
                annotations[symbol.name] = expression.atoms.drop(2)
            }

            else -> {
                main.add(expression)
            }
        }
    }

    private fun Atom.asType(): Atom =
        when (this) {
            is Symbol -> when (name) {
                "Int" -> GroundedType.INT
                "Double" -> GroundedType.DOUBLE
                "Boolean" -> GroundedType.BOOLEAN
                "String" -> GroundedType.STRING
                "Unit" -> GroundedType.UNIT
                "Atom" -> GroundedType.ATOM
                else -> TODO()
            }

            is ArrowType -> ArrowType(types = types.map { it.asType() })
            else -> TODO("atom=" + this)
        }

    companion object {
        const val MAIN = "__main"
    }
}