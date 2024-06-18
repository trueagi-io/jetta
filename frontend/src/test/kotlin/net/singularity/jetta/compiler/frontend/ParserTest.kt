package net.singularity.jetta.compiler.frontend

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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

}