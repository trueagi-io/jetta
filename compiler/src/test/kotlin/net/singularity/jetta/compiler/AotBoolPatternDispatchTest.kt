package net.singularity.jetta.compiler

import net.singularity.jetta.runtime.JettaProgram
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for a `Bool`-typed parameter dispatched against the *symbol* `True`
 * in a rule LHS. A MeTTa boolean is the bare symbol `True`/`False`, not a
 * `Grounded<Boolean>`; the pattern in `(= (ift True $then) $then)` lowers to a primitive
 * comparison `(== $param True)`. Codegen used to push the literal `True` via the generic
 * atom path (a `Symbol`) and then unwrap it as a `Grounded` — a `CHECKCAST Grounded` that
 * threw `ClassCastException` at runtime (observed in `tests/metta/e1_kb_write.metta`).
 * The fix loads `True`/`False` as the primitive boolean constant directly.
 *
 * The boolean value reaches `ift` through a grounded comparison (`(> 2 1)`) so this
 * isolates the pattern-literal side; the sibling coercions (returning a bare `True` from a
 * `Bool` function, or passing a literal `True` argument) are separate and not exercised here.
 */
class AotBoolPatternDispatchTest {

    private val program = """
        (: ift (-> Bool Atom Atom))
        (= (ift True ${'$'}then) ${'$'}then)
        !(println (ift (> 2 1) ok))
        !(println (ift (> 1 2) no))
    """.trimIndent()

    @Test
    fun `bool param dispatched against symbol True does not cast a Symbol to Grounded`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "jetta-boolpat-" + UUID.randomUUID())
        val srcDir = File(tmp, "src").apply { mkdirs() }
        val outDir = File(tmp, "out").apply { mkdirs() }
        try {
            val src = File(srcDir, "IftBool.metta").apply { writeText(program) }
            val code = Compiler(files = listOf(src.absolutePath), outputDir = outDir.absolutePath).compile()
            assertEquals(0, code, "compile should succeed")

            val output = runCompiled(outDir, "IftBool")
            // (> 2 1) is True  -> the True rule fires, returns the then-branch `ok`.
            // (> 1 2) is False -> no rule matches, so the call stays inert.
            assertEquals(listOf("ok", "(ift false no)"), output.trim().lines().map { it.trim() })
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * Bool-coercion #2: a `Bool`-typed function whose body is the bare symbol `True`/`False`.
     * The value must be returned as the primitive boolean the descriptor `()Z` expects, not the
     * `Symbol` object the generic atom path would push — that used to fail the verifier at
     * `ireturn` (a Symbol is not assignable to int).
     */
    @Test
    fun `bare True or False returned from a Bool function returns a primitive boolean`() {
        val src = """
            (: yes (-> Bool))
            (= (yes) True)
            (: no (-> Bool))
            (= (no) False)
            !(println (yes))
            !(println (no))
        """.trimIndent()
        assertEquals(listOf("true", "false"), compileAndRun(src, "RetBool"))
    }

    /**
     * Bool-coercion #3: a bare `True`/`False` literal passed to a `Bool` (primitive `Z`)
     * parameter. It must be pushed as the primitive constant, not the `Symbol` object — that
     * used to fail the verifier at the INVOKESTATIC call against a `Z` parameter.
     */
    @Test
    fun `literal True passed to a Bool parameter is coerced to a primitive boolean`() {
        val src = """
            (: ift (-> Bool Atom Atom))
            (= (ift True ${'$'}then) ${'$'}then)
            !(println (ift True ok))
        """.trimIndent()
        assertEquals(listOf("ok"), compileAndRun(src, "ArgBool"))
    }

    private fun compileAndRun(source: String, programName: String): List<String> {
        val tmp = File(System.getProperty("java.io.tmpdir"), "jetta-boolpat-" + UUID.randomUUID())
        val srcDir = File(tmp, "src").apply { mkdirs() }
        val outDir = File(tmp, "out").apply { mkdirs() }
        try {
            val src = File(srcDir, "$programName.metta").apply { writeText(source) }
            val code = Compiler(files = listOf(src.absolutePath), outputDir = outDir.absolutePath).compile()
            assertEquals(0, code, "compile should succeed")
            return runCompiled(outDir, programName).trim().lines().map { it.trim() }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun runCompiled(outDir: File, programName: String): String {
        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader)
        val clazz = loader.loadClass(programName)
        val main = clazz.methods.first { it.name == "__main" && it.parameterCount == 0 }
        JettaProgram.setDataDir(outDir.toPath())

        val saved = Thread.currentThread().contextClassLoader
        val savedOut = System.out
        val buffer = ByteArrayOutputStream()
        Thread.currentThread().contextClassLoader = loader
        System.setOut(PrintStream(buffer))
        try {
            main.invoke(null)
        } finally {
            System.setOut(savedOut)
            Thread.currentThread().contextClassLoader = saved
        }
        return buffer.toString()
    }
}
