package net.singularity.jetta.compiler

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*

class FunctionRewriter {
    private val typeInfo = mutableMapOf<String, Atom>()
    private val patterns = mutableMapOf<String, MutableList<Pattern>>()
    private val main = mutableListOf<Atom>()

    private data class Pattern(val pattern: Expression, val value: Expression)

    fun rewrite(source: ParsedSource): ParsedSource {
       source.code.forEach {
            when (it) {
                is Expression -> rewriteTopLevelExpression(it)
                else -> TODO()
            }
        }
        val mainPart = if (main.isNotEmpty()) listOf(mkMain()) else listOf()
        return ParsedSource(source.filename, mkFunctions() + mainPart)
    }

    private fun extractFormalParams(expression: Expression): List<Variable> =
        expression.atoms.drop(1).map {
            // FIXME: it might be a value
            println(it)
            it as Variable
        }

    private fun mkFunctions(): List<Atom> =
        patterns.map { (name, list) ->
            if (list.size == 1) {
                val pattern = list[0]
                FunctionDefinition(
                    name,
                    extractFormalParams(pattern.pattern),
                    typeInfo[name] as? ArrowType,
                    pattern.value
                )
            } else TODO()
        }

    private fun mkMain(): Atom =
        FunctionDefinition(
            MAIN,
            listOf(),
            null,
            Expression(listOf(Predefined.RUN_SEQ) + main)
        )

    private fun rewriteAtom(atom: Atom): Atom =
        when (atom) {
            is Expression -> rewriteExpression(atom)
            else -> atom
        }

    private fun mkArrow(expression: Expression): Atom =
        ArrowType(expression.atoms.drop(1).map {
            when (it) {
                is Expression -> mkArrow(it)
                else -> it
            }
        })

    private fun rewriteExpression(expression: Expression): Atom =
        when (expression.atoms[0]) {
            Predefined.ARROW -> mkArrow(expression)
            else -> expression
        }


    private fun rewriteTopLevelExpression(expression: Expression) {
        when (expression.atoms[0]) {
            Predefined.PATTERN -> {
                val pattern = expression.atoms[1] as Expression
                val symbol = pattern.atoms[0] as Symbol
                val list = patterns.getOrPut(symbol.name) { mutableListOf() }
                list.add(Pattern(pattern, expression.atoms[2] as Expression))
            }

            Predefined.TYPE -> {
                val symbol = expression.atoms[1] as Symbol
                typeInfo[symbol.name] = rewriteAtom(expression.atoms[2]).asType()
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