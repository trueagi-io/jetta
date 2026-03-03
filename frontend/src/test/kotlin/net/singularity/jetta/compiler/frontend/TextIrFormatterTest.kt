package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.*
import net.singularity.jetta.compiler.frontend.ir.formatter.TextIrFormatter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TextIrFormatterTest : BaseFrontendTest() {

    private val formatter = TextIrFormatter()

    @Test
    fun simpleExpression() {
        val (result, _) = resolve(
            "Add.metta",
            """
            (: foo (-> Int Int Int))
            (= (foo _x _y) (+ _x _y))
            """.trimIndent().replace('_', '$')
        )
        val output = formatter.format(result.code[0])
        assertContains(output, "(: foo (-> Int Int Int))")
        assertContains(output, $$"(= (foo $x:Int $y:Int)")
        assertContains(output, $$"(+ $x:Int $y:Int):Int")
    }

    @Test
    fun groundedLiterals() {
        val (result, _) = resolve(
            "Literal.metta",
            """
            (: foo (-> Int Int))
            (= (foo _x) (+ _x 1))
            """.trimIndent().replace('_', '$')
        )
        val output = formatter.format(result.code[0])
        assertContains(output, "1:Int")
    }

    @Test
    fun stringLiteral() {
        val (result, _) = resolve(
            "Hello.metta",
            """
            (: greet (-> String))
            (= (greet) "Hello")
            """.trimIndent()
        )
        val output = formatter.format(result)
        assertContains(output, "\"Hello\":String")
    }

    @Test
    fun lambdaFormatting() {
        val (result, _) = resolve(
            "Lambda.metta",
            """
            (: apply (-> (-> Int Int) Int Int))
            (= (apply _f _x) (_f _x))
            
            (: foo (-> Int Int))
            (= (foo _x) (apply (\ (_y) (+ _y 1)) _x))
            """.trimIndent().replace('_', '$')
        )
        val output = formatter.format(result)
        assertContains(output, "(\\")
        assertContains(output, $$"$y")
    }

    @Test
    fun seqTypeUsesStarNotation() {
        val (result, _) = resolve(
            "Seq.metta",
            """
            (@ foo multivalued)
            (: foo (-> Int))
            (= (foo) (seq 1 2 3))
            """.trimIndent()
        )
        val output = formatter.format(result)
        assertContains(output, "Int*")
        // Should NOT contain the old (Seq ...) format
        assertNotContains(output, "(Seq ")
    }

    @Test
    fun matchFormatting() {
        val (result, _) = resolve(
            "Match.metta",
            """
            (: sign (-> Int Int))
            (= (sign _x)
                (if (> _x 0) 1
                    (if (< _x 0) -1 0)))
            """.trimIndent().replace('_', '$')
        )
        val output = formatter.format(result.code[0])
        assertContains(output, "(: sign (-> Int Int))")
        assertContains(output, "if")
    }

    @Test
    fun parsedSourceHeader() {
        val (result, _) = resolve(
            "Header.metta",
            """
            (: foo (-> Int Int))
            (= (foo _x) (+ _x 1))
            """.trimIndent().replace('_', '$')
        )
        val output = formatter.format(result)
        assertContains(output, ";; source: Header.metta")
        assertContains(output, ";; fully typed IR after all transformations")
    }

    @Test
    fun arrowTypeFormatting() {
        val output = formatter.format(ArrowType(GroundedType.INT, GroundedType.INT, GroundedType.INT))
        assertEquals("(-> Int Int Int)", output)
    }

    @Test
    fun groundedTypeFormatting() {
        assertEquals("Int", formatter.format(GroundedType.INT))
        assertEquals("Boolean", formatter.format(GroundedType.BOOLEAN))
        assertEquals("String", formatter.format(GroundedType.STRING))
    }

    private fun assertContains(text: String, substring: String) {
        assert(substring in text) {
            "Expected to find '$substring' in:\n$text"
        }
    }

    private fun assertNotContains(text: String, substring: String) {
        assert(substring !in text) {
            "Expected NOT to find '$substring' in:\n$text"
        }
    }
}