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
        ctx.string()?.let {
            return visitString(it)
        }
        TODO(">>>" + ctx.text)
    }

    override fun visitString(ctx: JettaParser.StringContext): Atom {
        return Grounded(ctx.text.substring(1, ctx.text.length - 1), mkPosition(ctx.position))
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
            return Special(Predefined.TYPE, mkPosition(ctx.position))
        }
        ctx.pattern()?.let {
            return Special(Predefined.PATTERN, mkPosition(ctx.position))
        }
        ctx.arrow()?.let {
            return Special(Predefined.ARROW, mkPosition(ctx.position))
        }
        ctx.plus()?.let {
            return Special(Predefined.PLUS, mkPosition(ctx.position))
        }
        ctx.minus()?.let {
            return Special(Predefined.MINUS, mkPosition(ctx.position))
        }
        ctx.times()?.let {
            return Special(Predefined.TIMES, mkPosition(ctx.position))
        }
        ctx.if_()?.let {
            return Special(Predefined.IF, mkPosition(ctx.position))
        }
        ctx.eq()?.let {
            return Special(Predefined.COND_EQ, mkPosition(ctx.position))
        }
        ctx.neq()?.let {
            return Special(Predefined.COND_NEQ, mkPosition(ctx.position))
        }
        ctx.lt()?.let {
            return Special(Predefined.COND_LT, mkPosition(ctx.position))
        }
        ctx.gt()?.let {
            return Special(Predefined.COND_GT, mkPosition(ctx.position))
        }
        ctx.le()?.let {
            return Special(Predefined.COND_LE, mkPosition(ctx.position))
        }
        ctx.ge()?.let {
            return Special(Predefined.COND_GE, mkPosition(ctx.position))
        }
        ctx.lambda()?.let {
            return Special(Predefined.LAMBDA, mkPosition(ctx.position))
        }
        ctx.divide()?.let {
            return Special(Predefined.DIVIDE, mkPosition(ctx.position))
        }
        ctx.annotation()?.let {
            return Special(Predefined.ANNOTATION, mkPosition(ctx.position))
        }
        ctx.seq()?.let {
            return Special(Predefined.SEQ, mkPosition(ctx.position))
        }
        ctx.import_()?.let {
            return Special(Predefined.IMPORT,  mkPosition(ctx.position))
        }
        ctx.package_()?.let {
            return Special(Predefined.PACKAGE,  mkPosition(ctx.position))
        }
        TODO(ctx.text)
    }

    private fun Point.toPosition() = Position(line, column + 1)

    private fun mkPosition(position: Position?): SourcePosition? =
        position?.let { SourcePosition(filename, position.start.toPosition(), position.end.toPosition()) }

}