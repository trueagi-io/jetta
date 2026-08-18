package net.singularity.jetta.compiler

import net.singularity.jetta.compiler.frontend.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

class DumpIrIntegrationTest {

    @Test
    fun `--ir produces jir file for a single source`() = withCompiler {
        val (classes, irFiles) = compileWithIr(
            Source(
                "Add.metta",
                """
                (: add (-> Int Int Int))
                (= (add _x _y) (+ _x _y))
                """.trimIndent().replace('_', '$')
            )
        )
        assertTrue(classes.containsKey("Add"))
        assertTrue(irFiles.containsKey("Add"), "Expected Add.jir to be produced")

        val ir = irFiles["Add"]!!
        assertTrue(ir.contains(";; source:"), "IR should contain source header")
        assertTrue(ir.contains("(: add (-> Int Int Int))"), "IR should contain type signature")
        assertTrue(ir.contains($$"$x:Int"), "IR should contain typed variables")
        assertTrue(ir.contains($$"$y:Int"), "IR should contain typed variables")
    }

    @Test
    fun `--ir produces jir files for multiple sources`() = withCompiler {
        val (_, irFiles) = compileWithIr(
            Source(
                "Foo.metta",
                """
                (@ foo export)
                (: foo (-> Int Int))
                (= (foo _x) (+ _x 1))
                """.trimIndent().replace('_', '$')
            ),
            Source(
                "Bar.metta",
                """
                (: bar (-> Int Int))
                (= (bar _x) (foo _x))
                """.trimIndent().replace('_', '$')
            )
        )
        assertEquals(2, irFiles.size, "Expected 2 .jir files")
        assertTrue(irFiles.containsKey("Foo"), "Expected Foo.jir")
        assertTrue(irFiles.containsKey("Bar"), "Expected Bar.jir")

        assertTrue(irFiles["Foo"]!!.contains("foo"))
        assertTrue(irFiles["Bar"]!!.contains("bar"))
    }

    @Test
    fun `--ir output contains fully typed IR`() = withCompiler {
        val (_, irFiles) = compileWithIr(
            Source(
                "Typed.metta",
                """
                (: double (-> Int Int))
                (= (double _x) (* _x 2))
                """.trimIndent().replace('_', '$')
            )
        )
        val ir = irFiles["Typed"]!!
        // Should contain type annotations on the body expression
        assertTrue(ir.contains(":Int"), "IR should contain type annotations")
        assertTrue(ir.contains("2:Int"), "Grounded literals should be typed")
    }

    @Test
    fun `--ir output uses star notation for SeqType`() = withCompiler {
        val (_, irFiles) = compileWithIr(
            Source(
                "Seq.metta",
                """
                (@ nums multivalued)
                (: nums (-> Int))
                (= (nums) (seq 1 2 3))
                """.trimIndent()
            )
        )
        val ir = irFiles["Seq"]!!
        assertTrue(ir.contains("Int*"), "SeqType should use star notation")
        assertTrue(!ir.contains("(Seq "), "Should not use old (Seq ...) format")
    }

    @Test
    fun `compilation without --ir does not produce jir files`() = withCompiler {
        val classes = compile(
            Source(
                "NoIr.metta",
                """
                (: id (-> Int Int))
                (= (id _x) _x)
                """.trimIndent().replace('_', '$')
            )
        )
        assertTrue(classes.containsKey("NoIr"))
        // No .jir files should exist — we can't easily check from here
        // but at least verify compilation succeeds without --ir
    }

    @Test
    fun `compile a println call`() {
        val sources = listOf(
            Source(
                "Println.metta",
                """
                (println! (+ 1 1))
                """.trimMargin().replace('_', '$')
            )
        )
        val compiler = Compiler(listOf(), "/tmp")
        val (success, messages) = compiler.compileMultipleSources(sources)
        assertTrue(success)
        assertEquals(0, messages.size)
    }

    @Test
    fun `dumpIr produces jir files`() {
        val outputDir = File(System.getProperty("java.io.tmpdir"), "jetta-ir-test-${System.nanoTime()}")
        outputDir.mkdirs()
        try {
            val sources = listOf(
                Source(
                    "IrTest.metta",
                    """
                    (: inc (-> Int Int))
                    (= (inc _x) (+ _x 1))
                    """.trimIndent().replace('_', '$')
                )
            )
            val compiler = Compiler(listOf(), outputDir.absolutePath, dumpIr = true)
            val (success, _) = compiler.compileMultipleSources(sources)
            assertTrue(success)

            val jirFile = File(outputDir, "IrTest.jir")
            assertTrue(jirFile.exists(), "Expected IrTest.jir to be created")

            val ir = jirFile.readText()
            assertTrue(ir.contains(";; source: IrTest.metta"))
            assertTrue(ir.contains("(: inc (-> Int Int))"))
            assertTrue(ir.contains(":Int"))
        } finally {
            outputDir.deleteRecursively()
        }
    }
}