package net.singularity.jetta.compiler

import net.singularity.jetta.runtime.JettaProgram
import java.io.File
import java.net.URLClassLoader
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A term a meta-typed parameter held unevaluated, forced where the body needs its value, when the
 * term calls the program's OWN functions: `__force` reaches them through the `.jctx` linker table,
 * so this runs a real compiled binary rather than the backend harness. Measured on `metta-repl`.
 */
class HeldMetaParamLinkTest {

    /** Only the chosen `if` branch is reduced, and `(fac 2)` is a call, not a rewrite by its rule. */
    @Test
    fun `a held recursive term is forced to its value`() {
        val program = """
            (: fac (-> Number Number))
            (= (fac _n) (if (== _n 0) 1 (* _n (fac (- _n 1)))))
            (: e2 (-> Expression Number))
            (= (e2 _x) (+ _x 0))
            !(assertEqual (e2 (if (== 3 0) 1 (* 3 (fac 2)))) 6)
            !(assertEqual (e2 (fac 4)) 24)
        """.trimIndent().replace('_', '$')
        val tmp = File(System.getProperty("java.io.tmpdir"), "jetta-held-" + UUID.randomUUID())
        val srcDir = File(tmp, "src").apply { mkdirs() }
        val outDir = File(tmp, "out").apply { mkdirs() }
        try {
            val src = File(srcDir, "HeldRecursion.metta").apply { writeText(program) }
            val code = Compiler(files = listOf(src.absolutePath), outputDir = outDir.absolutePath).compile()
            assertEquals(0, code, "compile should succeed")
            val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader)
            val main = loader.loadClass("HeldRecursion").methods.first { it.name == "__main" && it.parameterCount == 0 }
            JettaProgram.setDataDir(outDir.toPath())
            val saved = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = loader
            try {
                main.invoke(null) // a failed assert throws out of `__main`
            } finally {
                Thread.currentThread().contextClassLoader = saved
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
