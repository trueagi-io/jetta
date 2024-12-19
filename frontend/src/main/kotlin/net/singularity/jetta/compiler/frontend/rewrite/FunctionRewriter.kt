package net.singularity.jetta.compiler.frontend.rewrite

import net.singularity.jetta.compiler.frontend.MessageCollector
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.rewrite.messages.ExpectVariableOrConstantButFoundMessage

class FunctionRewriter(val messageCollector: MessageCollector) : Rewriter {
    private val typeInfo = mutableMapOf<String, Atom>()
    private val annotations = mutableMapOf<String, List<Atom>>()
    private val patterns = mutableMapOf<String, MutableList<Pattern>>()
    private val main = mutableListOf<Atom>()

    private data class Pattern(val pattern: Expression, val value: Expression)

    override fun rewrite(source: ParsedSource): ParsedSource {
        source.code.forEach {
            when (it) {
                is Expression -> rewriteTopLevelExpression(it)
                else -> TODO()
            }
        }
        val mainPart = if (main.isNotEmpty()) listOf(mkMain()) else listOf()
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
            } else TODO()
        }

    private fun mkMain(): Atom =
        FunctionDefinition(
            MAIN,
            listOf(),
            null,
            Expression(listOf(Special(Predefined.RUN_SEQ)) + main)
        )

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
                list.add(Pattern(pattern, rewriteAtom(expression.atoms[2]) as Expression))
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
                else -> TODO()
            }

            is ArrowType -> ArrowType(types = types.map { it.asType() })
            else -> TODO("atom=" + this)
        }

    companion object {
        const val MAIN = "__main"
    }
}