package net.singularity.jetta.compiler.backend

import net.singularity.jetta.compiler.frontend.ParsedSource
import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.ir.Predefined.AND
import net.singularity.jetta.compiler.frontend.ir.Predefined.COND_EQ
import net.singularity.jetta.compiler.frontend.ir.Predefined.COND_GE
import net.singularity.jetta.compiler.frontend.ir.Predefined.COND_GT
import net.singularity.jetta.compiler.frontend.ir.Predefined.COND_LE
import net.singularity.jetta.compiler.frontend.ir.Predefined.COND_LT
import net.singularity.jetta.compiler.frontend.ir.Predefined.COND_NEQ
import net.singularity.jetta.compiler.frontend.ir.Predefined.IF
import net.singularity.jetta.compiler.frontend.ir.Predefined.MINUS
import net.singularity.jetta.compiler.frontend.ir.Predefined.OR
import net.singularity.jetta.compiler.frontend.ir.Predefined.TIMES
import net.singularity.jetta.compiler.frontend.ir.Predefined.XOR
import net.singularity.jetta.compiler.frontend.resolve.JvmMethod
import org.junit.jupiter.api.Test
import org.objectweb.asm.Type
import kotlin.test.assertEquals
import kotlin.test.fail

class FunctionGeneratorTest : GeneratorTestBase() {
    @Test
    fun generateFactorial() {
        // (: factorial (-> Int Int))
        // (= (factorial $n)
        //   (if (== $n 0) 1
        //       (* $n (factorial (- $n 1)))))
        val n = Variable("n", GroundedType.INT)
        val factorial = "factorial"
        val resolved = ResolvedSymbol(JvmMethod("Factorial", "factorial", "(I)I"), null,
            false)
        val function = FunctionDefinition(
            name = factorial,
            params = listOf(n),
            arrowType = ArrowType(types = listOf(GroundedType.INT, GroundedType.INT)),
            body = Expression(
                Special(IF),
                Expression(Special(COND_EQ), n, Grounded(0), type = GroundedType.INT),
                Grounded(1),
                Expression(
                    Special(TIMES), n, Expression(
                        Symbol(factorial),
                        Expression(Special(MINUS), n, Grounded(1), type = GroundedType.INT),
                        type = GroundedType.INT,
                        resolved = resolved
                    ), type = GroundedType.INT
                ),
                type = GroundedType.INT
            )
        )
        val generator = Generator()
        val source = ParsedSource("Factorial.metta", listOf(function))
        val result = generator.generate(source)[0]
        writeResult(result)
        val clazz = result.getClass()
        val r = clazz.getMethod(function.name, Int::class.java).invoke(null, 3)
        assertEquals(6, r)
    }

    @Test
    fun externalStaticCall() {
        // ten() is external
        // (: foo (-> Int))
        // (= (foo) (ten))
        val external = JvmMethod(
            owner = Type.getInternalName(External::class.java),
            name = "ten",
            descriptor = "()I"
        )
        val resolved = ResolvedSymbol(external, null, false)
        val function = FunctionDefinition(
            name = "foo",
            params = listOf(),
            arrowType = ArrowType(types = listOf(GroundedType.INT)),
            body = Expression(
                Symbol("ten"), type = GroundedType.INT, resolved = resolved
            )
        )
        val generator = Generator()
        val source = ParsedSource("ExternalStaticCall.metta", listOf(function))
        val result = generator.generate(source)[0]
        writeResult(result)
        val clazz = result.getClass()
        val r = clazz.getMethod(function.name).invoke(null)
        assertEquals(10, r)
    }

    @Test
    fun shortCircuitAnd() {
        val undefined = JvmMethod(
            owner = Type.getInternalName(External::class.java),
            name = "undefined",
            descriptor = "()I"
        )
        val resolved = ResolvedSymbol(undefined, null, false)
        val cond = Expression(
            Special(AND),
            Expression(
                Special(COND_GT),
                Variable("x", GroundedType.INT),
                Grounded(5),
                type = GroundedType.INT
            ),
            Expression(
                Special(COND_LE),
                Expression(
                    Symbol(undefined.name),
                    type = GroundedType.INT,
                    resolved = resolved
                ),
                Grounded(0),
                type = GroundedType.INT
            ),
            type = GroundedType.BOOLEAN
        )
        // (: foo (-> Int Int))
        // (= (foo $x) (if (and (> $x 5) ((undefined) <= 0)) 1 0))
        val function = FunctionDefinition(
            name = "foo",
            params = listOf(Variable("x")),
            arrowType = ArrowType(types = listOf(GroundedType.INT, GroundedType.INT)),
            body = Expression(
                Special(IF),
                cond,
                Grounded(1),
                Grounded(0),
                type = GroundedType.INT
            )
        )
        val generator = Generator()
        val source = ParsedSource("ShortCircuitAnd.metta", listOf(function))
        val result = generator.generate(source)[0]
        writeResult(result)
        val clazz = result.getClass()
        val method = clazz.getMethod(function.name, Int::class.java)
        val r = method.invoke(null, 3)
        assertEquals(0, r)
        try {
            method.invoke(null, 6)
            fail()
        } catch (_: Throwable) {
            // do nothing
        }
    }

    @Test
    fun shortCircuitOr() {
        val undefined = JvmMethod(
            owner = Type.getInternalName(External::class.java),
            name = "undefined",
            descriptor = "()I"
        )
        val resolved = ResolvedSymbol(undefined, null, false)
        val cond = Expression(
            Special(OR),
            Expression(
                Special(COND_GT),
                Variable("x", GroundedType.INT),
                Grounded(5),
                type = GroundedType.BOOLEAN
            ),
            Expression(
                Special(COND_LE),
                Expression(
                    Symbol(undefined.name),
                    type = GroundedType.INT,
                    resolved = resolved
                ),
                Grounded(0),
                type = GroundedType.BOOLEAN
            ),
            type = GroundedType.BOOLEAN
        )
        // (: foo (-> Int Int))
        // (= (foo $x) (if (or (> $x 5) ((undefined) <= 0)) 1 0))
        val function = FunctionDefinition(
            name = "foo",
            params = listOf(Variable("x")),
            arrowType = ArrowType(types = listOf(GroundedType.INT, GroundedType.INT)),
            body = Expression(
                Special(IF),
                cond,
                Grounded(1),
                Grounded(0),
                type = GroundedType.INT
            )
        )
        val generator = Generator()
        val source = ParsedSource("ShortCircuitAnd.metta", listOf(function))
        val result = generator.generate(source)[0]
        writeResult(result)
        val clazz = result.getClass()
        val method = clazz.getMethod(function.name, Int::class.java)
        val r = method.invoke(null, 6)
        assertEquals(1, r)
        try {
            method.invoke(null, 3)
            fail()
        } catch (_: Throwable) {
            // do nothing
        }
    }

    @Test
    fun booleanFuncEq() {
        testBooleanFunc(Special(COND_EQ)) { x, y -> x == y }
    }

    @Test
    fun booleanFuncNeq() {
        testBooleanFunc(Special(COND_NEQ)) { x, y -> x != y }
    }

    @Test
    fun booleanFuncGt() {
        testBooleanFunc(Special(COND_GT)) { x, y -> x > y }
    }

    @Test
    fun booleanFuncLt() {
        testBooleanFunc(Special(COND_LT)) { x, y -> x < y }
    }

    @Test
    fun booleanFuncGe() {
        testBooleanFunc(Special(COND_GE)) { x, y -> x >= y }
    }

    @Test
    fun booleanFuncLe() {
        testBooleanFunc(Special(COND_LE)) { x, y -> x <= y }
    }

    @Test
    fun booleanFuncAnd() {
        val expr = Expression(
            Special(AND),
            Expression(
                Special(COND_GT),
                Variable("x", GroundedType.INT),
                Grounded(5),
                type = GroundedType.BOOLEAN
            ),
            Expression(
                Special(COND_LE),
                Variable("y", GroundedType.INT),
                Grounded(6),
                type = GroundedType.BOOLEAN
            ),
            type = GroundedType.BOOLEAN
        )
        testBooleanFunc(expr) { x, y -> x > 5 && y <= 6 }
    }

    @Test
    fun booleanFuncOr() {
        val expr = Expression(
            Special(OR),
            Expression(
                Special(COND_GT),
                Variable("x", GroundedType.INT),
                Grounded(5),
                type = GroundedType.BOOLEAN
            ),
            Expression(
                Special(COND_LE),
                Variable("y", GroundedType.INT),
                Grounded(6),
                type = GroundedType.BOOLEAN
            ),
            type = GroundedType.BOOLEAN
        )
        testBooleanFunc(expr) { x, y -> x > 5 || y <= 6 }
    }

    @Test
    fun booleanFuncXor() {
        val expr = Expression(
            Special(XOR),
            Expression(
                Special(COND_GT),
                Variable("x", GroundedType.INT),
                Grounded(5),
                type = GroundedType.BOOLEAN
            ),
            Expression(
                Special(COND_LE),
                Variable("y", GroundedType.INT),
                Grounded(6),
                type = GroundedType.BOOLEAN
            ),
            type = GroundedType.BOOLEAN
        )
        testBooleanFunc(expr) { x, y -> (x > 5) xor (y <= 6) }
    }

    @Test
    fun booleanFuncWithIntLiteral() {
        val expr = Expression(
            Special(COND_EQ),
            Variable("x", GroundedType.INT),
            Grounded(1),
            type = GroundedType.BOOLEAN
        )
        testBooleanFunc(expr) { x, _ -> x == 1 }
    }

    @Test
    fun booleanFuncWithTrueLiteral() {
        val expr = Expression(
            Special(COND_EQ),
            Expression(
                Special(OR),
                Expression(
                    Special(COND_GT),
                    Variable("x", GroundedType.INT),
                    Grounded(5),
                    type = GroundedType.BOOLEAN
                ),
                Expression(
                    Special(COND_LE),
                    Variable("y", GroundedType.INT),
                    Grounded(6),
                    type = GroundedType.BOOLEAN
                ),
                type = GroundedType.BOOLEAN
            ),
            Grounded(true),
            type = GroundedType.BOOLEAN
        )
        testBooleanFunc(expr) { x, y -> x > 5 || y <= 6 }
    }

    @Test
    fun booleanFuncCompound() {
        val expr = Expression(
            Special(OR),
            Expression(
                Special(AND),
                Expression(
                    Special(COND_GT),
                    Variable("x", GroundedType.INT),
                    Grounded(1),
                    type = GroundedType.BOOLEAN
                ),
                Expression(
                    Special(COND_LE),
                    Variable("x", GroundedType.INT),
                    Grounded(5),
                    type = GroundedType.BOOLEAN
                ),
                type = GroundedType.BOOLEAN
            ),
            Expression(
                Special(AND),
                Expression(
                    Special(COND_GE),
                    Variable("y", GroundedType.INT),
                    Grounded(1),
                    type = GroundedType.BOOLEAN
                ),
                Expression(
                    Special(COND_LT),
                    Variable("y", GroundedType.INT),
                    Grounded(8),
                    type = GroundedType.BOOLEAN
                ),
                type = GroundedType.BOOLEAN
            ),
            type = GroundedType.BOOLEAN
        )
        testBooleanFunc(expr) { x, y -> (x > 1 && x <= 5) || (y >= 1 && y < 8) }
    }

    @Test
    fun nestedIf() {
        // (: foo (-> Int Int Int))
        // (= (foo $x $y)
        //   (if (== $x 0) 1
        //       (if (> $x $y) 2 3)))
        val x = Variable("x", GroundedType.INT)
        val y = Variable("y", GroundedType.INT)
        val function = FunctionDefinition(
            name = "foo",
            params = listOf(x, y),
            arrowType = ArrowType(types = listOf(GroundedType.INT, GroundedType.INT, GroundedType.INT)),
            body = Expression(
                Special(IF),
                Expression(Special(COND_EQ), x, Grounded(0), type = GroundedType.BOOLEAN),
                Grounded(1),
                Expression(
                    Special(IF),
                    Expression(Special(COND_LT), x, y, type = GroundedType.BOOLEAN),
                    Grounded(2),
                    Grounded(3),
                    type = GroundedType.INT
                ),
                type = GroundedType.INT
            )
        )
        val generator = Generator()
        val source = ParsedSource("NestedIf.metta", listOf(function))
        val result = generator.generate(source)[0]
        writeResult(result)
        val clazz = result.getClass()
        val method = clazz.getMethod(function.name, Int::class.java, Int::class.java)
        assertEquals(1, method.invoke(null, 0, 1))
        assertEquals(2, method.invoke(null, 1, 2))
        assertEquals(3, method.invoke(null, 2, 2))
    }

    @Test
    fun booleanParams() {
        val expr = Expression(
            Special(COND_EQ),
            Expression(
                Special(OR),
                Expression(
                    Special(COND_EQ),
                    Variable("x", GroundedType.BOOLEAN),
                    Grounded(true),
                    type = GroundedType.BOOLEAN
                ),
                Expression(
                    Special(COND_EQ),
                    Variable("y", GroundedType.BOOLEAN),
                    Grounded(false),
                    type = GroundedType.BOOLEAN
                ),
                type = GroundedType.BOOLEAN
            ),
            Grounded(true),
            type = GroundedType.BOOLEAN
        )
        val function = createBooleanFunc(expr, GroundedType.BOOLEAN)
        val generator = Generator()
        val source = ParsedSource("BooleanParams.metta", listOf(function))
        val result = generator.generate(source)[0]
        writeResult(result)
        val clazz = result.getClass()
        val method = clazz.getMethod(function.name, Boolean::class.java, Boolean::class.java)
        assertEquals(true, method.invoke(null, true, false))
        assertEquals(false, method.invoke(null, false, true))
        assertEquals(true, method.invoke(null, false, false))
        assertEquals(true, method.invoke(null, true, true))
    }

    private fun testBooleanFunc(expr: Expression, test: (Int, Int) -> Boolean) {
        val function = createBooleanFunc(expr, GroundedType.INT)
        val generator = Generator()
        val source = ParsedSource("BooleanExample.metta", listOf(function))
        val result = generator.generate(source)[0]
        writeResult(result)
        val clazz = result.getClass()
        (1..10).flatMap { x ->
            (1..10).map { y -> x to y }
        }.forEach { (x, y) ->
            val r = clazz.getMethod(function.name, Int::class.java, Int::class.java).invoke(null, x, y)
            assertEquals(test(x, y), r, "x=$x y=$y")
        }
    }

    private fun testBooleanFunc(op: Special, test: (Int, Int) -> Boolean) {
        // (: booleanFunc (-> Int Int Boolean))
        // (= (ge $x $y) (<op> $x $y))
        testBooleanFunc(
            Expression(
                op,
                Variable("x", GroundedType.INT),
                Variable("y", GroundedType.INT),
                type = GroundedType.BOOLEAN
            ), test
        )
    }

    private fun createBooleanFunc(expr: Expression, paramType: GroundedType): FunctionDefinition =
        FunctionDefinition(
            name = "booleanFunc",
            params = listOf(Variable("x", paramType), Variable("y", paramType)),
            arrowType = ArrowType(types = listOf(paramType, paramType, GroundedType.BOOLEAN)),
            body = expr
        )
}