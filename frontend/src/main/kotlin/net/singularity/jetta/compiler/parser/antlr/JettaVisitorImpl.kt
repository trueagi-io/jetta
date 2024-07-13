package net.singularity.jetta.compiler.parser.antlr

import net.singularity.compiler.frontend.parser.antlr.generated.JettaBaseVisitor
import net.singularity.compiler.frontend.parser.antlr.generated.JettaParser
import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import org.antlr.v4.kotlinruntime.ast.Point
import org.antlr.v4.kotlinruntime.ast.Position

class JettaVisitorImpl(private val filename: String) : JettaBaseVisitor<Any?>() {
    private var parsedSource: ParsedSource? = null
    fun getParsedSource(): ParsedSource? = parsedSource

    override fun visitProgram(ctx: JettaParser.ProgramContext) {
        val list = mutableListOf<Atom>()
        ctx.expression().forEach {
            list.add(visitExpression(it))
        }
        parsedSource = ParsedSource(filename, list)
    }

    override fun visitExpression(ctx: JettaParser.ExpressionContext): Expression {
        val list = mutableListOf<Atom>()
        ctx.atom().forEach {
            list.add(visitAtom(it))
        }
        return Expression(list, position = mkPosition(ctx.position))
    }

    override fun visitAtom(ctx: JettaParser.AtomContext): Atom {
        ctx.symbol()?.let {
            return Symbol(it.text, mkPosition(ctx.position))
        }
        ctx.variable()?.let {
            return Variable(it.identifier().text, position = mkPosition(ctx.position))
        }
        ctx.special()?.let {
            return visitSpecial(it)
        }
        ctx.expression()?.let {
            return visitExpression(it)
        }
        ctx.number()?.let {
            return visitNumber(it)
        }
        TODO(">>>" + ctx.text)
    }

    override fun visitNumber(ctx: JettaParser.NumberContext): Atom {
        ctx.integer()?.let {
            return Grounded(it.text.toInt(), mkPosition(ctx.position))
        }
        ctx.double()?.let {
            return Grounded(it.text.toDouble(), mkPosition(ctx.position))
        }
        TODO()
    }

    override fun visitSpecial(ctx: JettaParser.SpecialContext): Atom {
        ctx.type()?.let {
            return Predefined.TYPE
        }
        ctx.pattern()?.let {
            return Predefined.PATTERN
        }
        ctx.arrow()?.let {
            return Predefined.ARROW
        }
        ctx.plus()?.let {
            return Predefined.PLUS
        }
        ctx.minus()?.let {
            return Predefined.MINUS
        }
        ctx.times()?.let {
            return Predefined.TIMES
        }
        ctx.if_()?.let {
            return Predefined.IF
        }
        ctx.eq()?.let {
            return Predefined.COND_EQ
        }
        ctx.neq()?.let {
            return Predefined.COND_NEQ
        }
        ctx.lt()?.let {
            return Predefined.COND_LT
        }
        ctx.gt()?.let {
            return Predefined.COND_GT
        }
        ctx.le()?.let {
            return Predefined.COND_LE
        }
        ctx.ge()?.let {
            return Predefined.COND_GE
        }
        ctx.lambda()?.let {
            return Predefined.LAMBDA
        }
        ctx.divide()?.let {
            return Predefined.DIVIDE
        }
        TODO()
    }

    private fun Point.toPosition() = Position(line, column + 1)

    private fun mkPosition(position: Position?): SourcePosition? =
        position?.let { SourcePosition(filename, position.start.toPosition(), position.end.toPosition()) }

}