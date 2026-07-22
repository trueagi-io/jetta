package net.singularity.jetta.compiler

import net.singularity.jetta.runtime.JettaProgram
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AOT (compiled-binary) counterpart to [net.singularity.jetta.repl.VariableHeadDispatchTest].
 *
 * That test drives variable-head dispatch through the REPL, where a live `JitEnv` lets
 * `JettaCallSite` JIT-compile the application. A `jetta`-run binary has NO `JitEnv`: it must
 * link `($f x)` against the compiled method via the `.jctx` linker table loaded by
 * `JettaProgram.init` (P1). This test compiles a program to disk and runs its generated
 * `__main` the way the CLI does — no `JitEnv` in scope — so it exercises exactly the AOT
 * registry path, guarding the behaviour the corpus harness observed (`(apply inc 5)` used to
 * stay the inert `(+ 5 1)`; it now evaluates to `6`).
 */
class AotVariableHeadDispatchTest {

    private val program = """
        (= (inc ${'$'}x) (+ ${'$'}x 1))
        (= (dbl ${'$'}x) (* ${'$'}x 2))
        (= (apply ${'$'}f ${'$'}x) (${'$'}f ${'$'}x))
        (= (twice ${'$'}f ${'$'}x) (${'$'}f (${'$'}f ${'$'}x)))
        (= (app2 ${'$'}f ${'$'}g ${'$'}x) (${'$'}f (${'$'}g ${'$'}x)))
        !(println (apply inc 5))
        !(println (twice inc 10))
        !(println (twice dbl 3))
        !(println (app2 inc dbl 5))
    """.trimIndent()

    @Test
    fun `compiled binary links variable-head applications`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "jetta-aot-" + UUID.randomUUID())
        val srcDir = File(tmp, "src").apply { mkdirs() }
        val outDir = File(tmp, "out").apply { mkdirs() }
        try {
            val src = File(srcDir, "AotApply.metta").apply { writeText(program) }
            val code = Compiler(files = listOf(src.absolutePath), outputDir = outDir.absolutePath).compile()
            assertEquals(0, code, "compile should succeed")

            val output = runCompiled(outDir, "AotApply")
            // apply inc 5 = 6 ; twice inc 10 = 12 ; twice dbl 3 = 12 ; app2 inc dbl 5 = 11
            assertEquals(listOf("6", "12", "12", "11"), output.trim().lines().map { it.trim() })
        } finally {
            tmp.deleteRecursively()
        }
    }

    // Program synthesis (PeTTa's synth benchmark, `seq`→`sq` to avoid the reserved `seq`):
    // a non-deterministic generator `gen`, a meta-interpreter `ev` that applies operators
    // carried as DATA (`(ev (Bin $op …)) → ($op …)`), a checker, and `render` that composes
    // the winning program from quoted pieces via Form-2 pattern-`let`. Exercises the whole
    // stack end to end in a COMPILED binary: operator-as-data, grounded-op MH dispatch,
    // multivalued analysis through lambdas, `empty`/`unique`, and Form-2 `let`.
    private val synth = """
        (= (sq nat 1) 1) (= (sq nat 2) 2) (= (sq nat 3) 3) (= (sq nat 4) 4) (= (sq nat 5) 5)
        (= (len nat) 5)
        (= (sq fib 1) 1) (= (sq fib 2) 1) (= (sq fib 3) 2) (= (sq fib 4) 3) (= (sq fib 5) 5) (= (sq fib 6) 8)
        (= (len fib) 6)
        (= (gen ${'$'}d) (superpose (N (C 1) (C 2) (X 1) (X 2))))
        (= (gen ${'$'}d)
           (if (> ${'$'}d 0)
               (Bin (superpose (+ - *)) (gen (- ${'$'}d 1)) (gen (- ${'$'}d 1)))
               (empty)))
        (= (ev N ${'$'}s ${'$'}n) ${'$'}n)
        (= (ev (C ${'$'}c) ${'$'}s ${'$'}n) ${'$'}c)
        (= (ev (X ${'$'}k) ${'$'}s ${'$'}n) (sq ${'$'}s (- ${'$'}n ${'$'}k)))
        (= (ev (Bin ${'$'}op ${'$'}a ${'$'}b) ${'$'}s ${'$'}n) (${'$'}op (ev ${'$'}a ${'$'}s ${'$'}n) (ev ${'$'}b ${'$'}s ${'$'}n)))
        (= (check ${'$'}e ${'$'}s ${'$'}n)
           (if (> ${'$'}n (len ${'$'}s))
               True
               (if (== (ev ${'$'}e ${'$'}s ${'$'}n) (sq ${'$'}s ${'$'}n))
                   (check ${'$'}e ${'$'}s (+ ${'$'}n 1))
                   False)))
        (= (render N) (quote n))
        (= (render (C ${'$'}c)) (quote ${'$'}c))
        (= (render (X ${'$'}k)) (quote (x (- n ${'$'}k))))
        (= (render (Bin ${'$'}op ${'$'}a ${'$'}b))
           (let* (((quote ${'$'}ra) (render ${'$'}a)) ((quote ${'$'}rb) (render ${'$'}b)))
             (quote (${'$'}op ${'$'}ra ${'$'}rb))))
        (= (solve ${'$'}s ${'$'}d)
           (let ${'$'}e (gen ${'$'}d)
             (if (check ${'$'}e ${'$'}s 3)
                 (let (quote ${'$'}body) (render ${'$'}e)
                   (quote (= (x n) ${'$'}body)))
                 (empty))))
        !(println (unique (solve fib 1)))
    """.trimIndent()

    @Test
    fun `compiled binary runs the program-synthesis benchmark`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "jetta-synth-" + UUID.randomUUID())
        val srcDir = File(tmp, "src").apply { mkdirs() }
        val outDir = File(tmp, "out").apply { mkdirs() }
        try {
            val src = File(srcDir, "AotSynth.metta").apply { writeText(synth) }
            val code = Compiler(files = listOf(src.absolutePath), outputDir = outDir.absolutePath).compile()
            assertEquals(0, code, "compile should succeed")

            val output = runCompiled(outDir, "AotSynth").trim()
            // The fib recurrence must be synthesized: x(n) = x(n-1) + x(n-2).
            assertTrue(
                output.contains("(= (x n) (+ (x (- n 1)) (x (- n 2))))"),
                "expected the fib recurrence in: $output",
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    // b3_direct's "retrieve the variable binding by constructing an expression with it": a
    // variable in expression-HEAD position, `($x (green $x))`. `(green $x)` backchains
    // (binds `$x`=Fritz through upward-propagated foliation, yields T); the body `($x <T>)`
    // reaches `JettaCallSite.dispatch` with `$x` as an UNRESOLVED Variable head. It must be
    // resolved against the Matcher bindings to Fritz before building the inert form, else
    // `($x T)` unifies as a PATTERN against the space `=` rules (pattern-var `$x` matches
    // `(green $x)`'s head) and wrongly reduces to `(And (croaks T) (eat_flies T))`. Also
    // covers `(match &self (= ($p Fritz) T) $p)` — a space query whose pattern has a variable
    // in head position, binding `$p` to each matching rule head (`croaks`, `eat_flies`).
    private val varHeadConstruct = """
        (= (croaks Fritz) T)
        (= (eat_flies Fritz) T)
        (= (And T T) T)
        (= (frog ${'$'}x) (And (croaks ${'$'}x) (eat_flies ${'$'}x)))
        (= (green ${'$'}x) (frog ${'$'}x))
        !(println (${'$'}x (green ${'$'}x)))
        !(assertEqual (${'$'}x (green ${'$'}x)) (Fritz T))
        !(assertEqualToResult (match &self (= (${'$'}p Fritz) T) ${'$'}p) (croaks eat_flies))
    """.trimIndent()

    @Test
    fun `variable-head application substitutes the bound head and constructs the inert expression`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "jetta-varhead-" + UUID.randomUUID())
        val srcDir = File(tmp, "src").apply { mkdirs() }
        val outDir = File(tmp, "out").apply { mkdirs() }
        try {
            val src = File(srcDir, "VarHeadConstruct.metta").apply { writeText(varHeadConstruct) }
            val code = Compiler(files = listOf(src.absolutePath), outputDir = outDir.absolutePath).compile()
            assertEquals(0, code, "compile should succeed")

            // No throw = both assertEqual / assertEqualToResult held; println pins assert-3's value.
            val output = runCompiled(outDir, "VarHeadConstruct").trim()
            assertEquals("(Fritz T)", output.lines().first().trim())
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Load and run the generated `__main` in-process, with no JitEnv, capturing stdout. */
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
