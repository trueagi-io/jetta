package net.singularity.jetta.compiler.frontend

import net.singularity.jetta.compiler.frontend.ir.Run
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Symbol
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
        justParse("""(println! "Hello")""")
    }

    @Test
    fun `parse an empty string`() {
        justParse("""(println! "")""")
    }

    @Test
    fun `parse special characters in the string`() {
       justParse("""(println! "Hello\n\tworld")""")
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

    @Test
    fun `parse identifier with trailing bang`() {
        val program = justParse("(bind! foo bar)")
        assertEquals(1, program.code.size)
        val expr = program.code[0] as Expression
        assertEquals(3, expr.atoms.size)
        val head = expr.atoms[0] as Symbol
        assertEquals("bind!", head.name)
    }

    @Test
    fun `parse import bang as plain symbol`() {
        val program = justParse("!(import! &self utils)")
        assertEquals(1, program.code.size)
        assertTrue(program.code[0] is Run)
        val run = program.code[0] as Run
        val expr = run.expression
        assertEquals(3, expr.atoms.size)
        assertEquals("import!", (expr.atoms[0] as Symbol).name)
        assertEquals("&self", (expr.atoms[1] as Symbol).name)
        assertEquals("utils", (expr.atoms[2] as Symbol).name)
    }

    @Test
    fun `parse change-state bang`() {
        val program = justParse("(change-state! foo 1)")
        val expr = program.code[0] as Expression
        assertEquals("change-state!", (expr.atoms[0] as Symbol).name)
    }

    @Test
    fun `parse assertEqual without trailing bang`() {
        val program = justParse("(assertEqual a b)")
        val expr = program.code[0] as Expression
        assertEquals("assertEqual", (expr.atoms[0] as Symbol).name)
    }

    @Test
    fun `bare bang inside expression is a parse error`() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        parser.parse(
            Source("BareBang.metta", "(foo !)"),
            messageCollector
        )
        assertTrue(messageCollector.list().any { it is ParseErrorMessage })
    }

    @Test
    fun `not-equals is a single token after grammar relax`() {
        // Verifies that '!=' still lexes as NEQ rather than IDENT/BANG/EQUAL.
        val program = justParse("(foo != bar)")
        val expr = program.code[0] as Expression
        assertEquals(3, expr.atoms.size)
        // The middle atom is a Special(NEQ), not a symbol or two tokens.
        // Surface check: stringification stays stable.
        assertEquals("(foo != bar)", expr.toString())
    }

    @Test
    fun `parse identifier with trailing question`() {
        val program = justParse("(Frog? Sam)")
        val expr = program.code[0] as Expression
        assertEquals("Frog?", (expr.atoms[0] as Symbol).name)
    }

    @Test
    fun `parse leading-dot identifier`() {
        val program = justParse("(.tv x stv)")
        val expr = program.code[0] as Expression
        assertEquals(".tv", (expr.atoms[0] as Symbol).name)
    }

    @Test
    fun `parse percent-bracketed meta-type`() {
        val program = justParse("(: Left (-> %Undefined% Either))")
        val expr = program.code[0] as Expression
        val arrow = expr.atoms[2] as Expression
        assertEquals("%Undefined%", (arrow.atoms[1] as Symbol).name)
        assertEquals("Either", (arrow.atoms[2] as Symbol).name)
    }

    @Test
    fun `parse standalone percent operator`() {
        val program = justParse("(% 21 17)")
        val expr = program.code[0] as Expression
        assertEquals("%", (expr.atoms[0] as Symbol).name)
    }

    @Test
    fun `parse comma as conjunction operator`() {
        val program = justParse("(match _self (, (Frog _x) (implies (Frog _x) _y)) _y)")
        val expr = program.code[0] as Expression
        val conj = expr.atoms[2] as Expression
        assertEquals(",", (conj.atoms[0] as Symbol).name)
    }

    @Test
    fun `parse double-colon as cons-style symbol`() {
        // `::` is a Lisp/Haskell convention for list cons used in MeTTa samples.
        // It must parse as a single Symbol, not as two `:` (COLON) tokens.
        val program = justParse("(:: 3 (:: 7 nil))")
        val expr = program.code[0] as Expression
        assertEquals("::", (expr.atoms[0] as Symbol).name)
        val inner = expr.atoms[2] as Expression
        assertEquals("::", (inner.atoms[0] as Symbol).name)
    }

    @Test
    fun `parse double-colon with suffix`() {
        // `::foo` is also a valid identifier — same start, plus body characters.
        val program = justParse("(::foo bar)")
        val expr = program.code[0] as Expression
        assertEquals("::foo", (expr.atoms[0] as Symbol).name)
    }

    @Test
    fun `single colon stays a type annotation`() {
        // Critical: extending IDENT to accept `::` must not break the bare `:`
        // type-annotation form. ANTLR's longest-match keeps a lone `:` as the
        // COLON token, so `(: foo Int)` still parses as a type form.
        val program = justParse("(: foo Int)")
        val expr = program.code[0] as Expression
        // Head is a Special(":"), not a Symbol — the rewriter / resolver path
        // for type annotations is unaffected.
        val head = expr.atoms[0]
        assertEquals("class net.singularity.jetta.compiler.frontend.ir.Special", head.javaClass.toString())
    }

    /**
     * The PRIME convention — `$type'`, `$params'` — is used throughout the reference
     * `stdlib.metta`. A quote is a token of its own, so without `'` in the identifier
     * CONTINUATION class `$type'` lexed as `$type` followed by a QUOTE that swallowed the next
     * atom, and the file could not be parsed at all. A name still cannot START with a quote, so
     * `'(a b)` is unaffected — the last assertion pins that.
     */
    @Test
    fun primeInNames() {
        val parser = createParserFacade()
        val messageCollector = MessageCollector()
        val program = parser.parse(
            Source(
                "Prime.metta",
                """
                (let ${'$'}type' (cdr-atom ${'$'}type) (foo ${'$'}type'))
                (bar' baz'qux)
                (keep '(a b))
                """.trimIndent()
            ),
            messageCollector
        )
        assertTrue(messageCollector.list().isEmpty(), "messages: ${messageCollector.list()}")
        assertEquals(
            "[(let ${'$'}type' (cdr-atom ${'$'}type) (foo ${'$'}type')), (bar' baz'qux), (keep (quote (a b)))]",
            program.code.toString()
        )
    }
}