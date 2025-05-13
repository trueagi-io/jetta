package net.singularity.jetta.compiler.frontend

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
}