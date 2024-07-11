package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import kotlin.test.Test
import kotlin.test.assertEquals

class FloatingPointTest : GeneratorTestBase() {
    @Test
    fun addDouble() {
        val expr = Expression(
            Predefined.PLUS,
            Variable("x", GroundedType.DOUBLE),
            Variable("y", GroundedType.DOUBLE),
            type = GroundedType.DOUBLE
        )
        val function = createFunc(expr)
        val generator = Generator()
        val source = ParsedSource("AddDouble.metta", listOf(function))
        val result = generator.generate(source)[0]
        writeResult(result)
        val clazz = result.getClass()
        val r = clazz.getMethod(function.name, Double::class.java, Double::class.java).invoke(null, 1.0, 2.0)
        assertEquals(3.0, r)
    }

    @Test
    fun addConstant() {
        val expr = Expression(
            Predefined.PLUS,
            Variable("x", GroundedType.DOUBLE),
            Grounded(2.0),
            type = GroundedType.DOUBLE
        )
        val function = createFunc(expr)
        val generator = Generator()
        val source = ParsedSource("AddDouble.metta", listOf(function))
        val result = generator.generate(source)[0]
        writeResult(result)
        val clazz = result.getClass()
        val r = clazz.getMethod(function.name, Double::class.java).invoke(null, 10.0)
        assertEquals(12.0, r)
    }

    @Test
    fun intToDoubleImplicitCast() {
        val expr = Expression(
            Predefined.PLUS,
            Variable("x", GroundedType.INT),
            Variable("y", GroundedType.DOUBLE),
            type = GroundedType.DOUBLE
        )
        val function = FunctionDefinition(
            name = "floatingPointFunc",
            params = listOf(Variable("x", GroundedType.INT), Variable("y", GroundedType.DOUBLE)),
            arrowType = ArrowType(types = listOf(GroundedType.INT, GroundedType.DOUBLE, GroundedType.DOUBLE)),
            body = expr
        )
        val generator = Generator()
        val source = ParsedSource("IntDoubleImplicitCast.metta", listOf(function))
        val result = generator.generate(source)[0]
        writeResult(result)
        val clazz = result.getClass()
        val r = clazz.getMethod(function.name, Int::class.java, Double::class.java).invoke(null, 1, 2.0)
        assertEquals(3.0, r)
    }

    private fun createFunc(expr: Expression): FunctionDefinition {
        fun collectVariables(expression: Expression, acc: MutableList<Variable>) {
            expression.atoms.forEach {
                when (it) {
                    is Variable -> acc.add(it)
                    is Expression -> collectVariables(it, acc)
                    is Symbol -> TODO()
                    else -> {}
                }
            }
        }
        fun mkArrowType(vars: List<Variable>, exprType: Atom): ArrowType {
            val list = mutableListOf<Atom>()
            list.addAll(vars.toSet().sortedBy { it.name }.map { it.type!! })
            list.add(exprType)
            return ArrowType(list)
        }
        val vars = mutableListOf<Variable>()
        collectVariables(expr, vars)
        return FunctionDefinition(
            name = "floatingPointFunc",
            params = vars.map { it },
            arrowType = mkArrowType(vars, expr.type!!),
            body = expr
        )
    }
}