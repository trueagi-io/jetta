package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.ir.Match
import net.singularity.jetta.compiler.frontend.ir.MatchBranch
import net.singularity.jetta.compiler.frontend.rewrite.messages.ExpectVariableOrConstantButFoundMessage

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
                is Variable -> Variable(changeVariables[atom.name]!!) // FIXME: handle error here
                is Expression -> rewriteExpression(atom)
                is Lambda -> rewriteLambda(atom)
                else -> atom
            }

        operator fun set(name: String, newName: String) {
            changeVariables[name] = newName
        }
    }

    private fun substitute(arrowType: ArrowType?, pattern: Pattern): Atom {
        val changeVariables = ChangeVariables()

        if (isConstantExpression(pattern.value)) return pattern.value
        val lambdaParams = mutableListOf<Variable>()
        val types = mutableListOf<Atom>()
        pattern.pattern.atoms.drop(1).forEachIndexed { index, atom ->
            if (atom is Variable) {
                changeVariables[atom.name] = mkParamName(index)
                lambdaParams.add(atom)
            }
        }
        if (arrowType != null) types.add(arrowType.types.last())
        return changeVariables.rewriteAtom(pattern.value)
    }

    private fun mkCond(params: List<Variable>, pattern: Expression): Expression? {
        val cond = mutableListOf<Expression>()
        if (pattern.atoms.size == 1) return null
        params.zip(pattern.atoms.drop(1)).forEach { (variable, atom) ->
            if (atom is Grounded<*>) {
                cond.add(Expression(Special(Predefined.COND_EQ), variable, atom))
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
            if (list.size == 1) {
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
                        MatchBranch(
                            mkCond(params, it.pattern),
                            substitute(arrowType, it)
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
        var count = 0

        val calls = main.map {
            val fnName = "__main_${count++}"
            result.add(
                FunctionDefinition(
                    fnName,
                    listOf(),
                    null,
                    it
                )
            )
            Expression(Symbol(fnName))
        }
        result.add(
            FunctionDefinition(
                MAIN,
                listOf(),
                null,
                Expression(listOf(Special(Predefined.RUN_SEQ)) + calls)
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

    private fun rewriteExpression(expression: Expression): Atom {
        val func = expression.atoms[0]
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
                else -> TODO()
            }

            is ArrowType -> ArrowType(types = types.map { it.asType() })
            else -> TODO("atom=" + this)
        }

    companion object {
        const val MAIN = "__main"
    }
}