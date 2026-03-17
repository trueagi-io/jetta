package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.Run
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.parser.messages.ParseErrorMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParserTest : BaseFrontendTest() {
    @Test
    fun simpleAtoms() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "SimpleAtoms.metta",
                """
                (hello world)
                (welcome)
                """.trimIndent()
            ),
            messageCollector
        )
        assertEquals("[(hello world), (welcome)]", program.code.toString())
    }

    @Test
    fun comments() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "Comments.metta",
                """
                ;;;;;;;;;;
                ; comment
                ;;;;;;;;;;
                (hello 
                world) ; comment
                (welcome)
                """.trimIndent()
            ),
            messageCollector
        )
        assertEquals("[(hello world), (welcome)]", program.code.toString())
    }

    @Test
    fun parseError() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        parser.parse(
            Source(
                "ParseError.metta",
                """
                (hello world))
                (welcome)
                """.trimIndent()
            ),
            messageCollector
        )
        assertEquals(1, messageCollector.list().size)
        assertTrue(messageCollector.list()[0] is ParseErrorMessage)
    }

    @Test
    fun variablesWithId() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        parser.parse(
            Source(
                "VariablesWithId.metta",
                """
                (: foo (-> Int Int))
                (= (foo _x#52) (+ _x#52 1))
                """.trimIndent().replace('_', '$')
            ),
            messageCollector
        )
        assertEquals(0, messageCollector.list().size)
    }

    @Test
    fun dashInIdent() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "DashInIdent.metta",
                """
                (hello-world)
                """.trimIndent()
            ),
            messageCollector
        )
        assertEquals("[(hello-world)]", program.code.toString())
    }

    @Test
    fun `parse import`() {
        justParse("""
                (import net.singularity.jetta.example.bar)
                (: foo (-> Int Int))
                (= (foo _x) (bar _x))
                """)
    }

    @Test
    fun `parse package`() {
        justParse("""
                (package net.singularity.jetta.example)
                (: foo (-> Int Int))
                (= (foo _x) (bar _x))
                """)
    }

    @Test
    fun `parse a string`() {
        justParse("""(println "Hello")""")
    }

    @Test
    fun `parse an empty string`() {
        justParse("""(println "")""")
    }

    @Test
    fun `parse special characters in the string`() {
       justParse("""(println "Hello\n\tworld")""")
    }

    @Test
    fun `parse long literal`() {
        justParse("""(seed 10L)""").let {
            val expr = it.code[0] as Expression
            assertEquals(2, expr.atoms.size)
        }
    }

    @Test
    fun `parse assertion`() {
        val program = justParse("""
            !(assertEqual (frog Sam) T)
        """)

        assertEquals(1, program.code.size)
        assertTrue(program.code[0] is Run)

        val assertion = program.code[0] as Run
        assertEquals("(assertEqual (frog Sam) T)", assertion.expression.toString())
    }

    @Test
    fun `parse assertions with ordinary expressions`() {
        val program = justParse("""
            (Frog Sam)
            !(assertEqualToResult (Frog Sam) ((Frog Sam)))
        """)

        assertEquals(2, program.code.size)
        assertTrue(program.code[0] is Expression)
        assertTrue(program.code[1] is Run)
    }

    @Test
    fun `parse mixed top level expressions and runs preserving order`() {
        val program = justParse("""
            (Fruit apple)
            !(foo)
            (City Paris)
            !(bar)
        """)

        assertEquals(4, program.code.size)
        assertTrue(program.code[0] is Expression)
        assertTrue(program.code[1] is Run)
        assertTrue(program.code[2] is Expression)
        assertTrue(program.code[3] is Run)

        assertEquals("(Fruit apple)", program.code[0].toString())
        assertEquals("!(foo)", program.code[1].toString())
        assertEquals("(City Paris)", program.code[2].toString())
        assertEquals("!(bar)", program.code[3].toString())
    }

    @Test
    fun `parse empty expression`() {
        val program = justParse("""
            ()
        """)

        assertEquals(1, program.code.size)
        assertTrue(program.code[0] is Expression)
        assertEquals("()", program.code[0].toString())
    }

    @Test
    fun `parse assertion with empty expected result`() {
        val program = justParse("""
            !(assertEqualToResult (frog Fritz) ())
        """)

        assertEquals(1, program.code.size)
        assertTrue(program.code[0] is Run)
    }

    @Test
    fun `parse quoted symbol inside expression`() {
        val program = justParse("""
            (assertEqual 'Frog 'Frog)
        """)

        assertEquals(1, program.code.size)
        assertTrue(program.code[0] is Expression)

        val expr = program.code[0] as Expression
        assertEquals(3, expr.atoms.size)
        assertEquals("(quote Frog)", expr.atoms[1].toString())
        assertEquals("(quote Frog)", expr.atoms[2].toString())
    }

    @Test
    fun `parse quoted expression inside seq`() {
        val program = justParse("""
            !(assertEqualToResult (Frog Sam) (seq '(Frog Sam)))
        """)

        assertEquals(1, program.code.size)
        assertTrue(program.code[0] is Run)

        val assertion = program.code[0] as Run
        val call = assertion.expression
        assertEquals(3, call.atoms.size)

        assertTrue(call.atoms[2] is Expression)
        val seq = call.atoms[2] as Expression
        assertEquals("(seq (quote (Frog Sam)))", seq.toString())
    }

    @Test
    fun `parse quoted empty expression inside seq`() {
        val program = justParse("""
            !(assertEqualToResult (frog Fritz) (seq '()))
        """)

        assertEquals(1, program.code.size)
        assertTrue(program.code[0] is Run)
    }
}