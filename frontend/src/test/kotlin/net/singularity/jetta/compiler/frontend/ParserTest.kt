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
}