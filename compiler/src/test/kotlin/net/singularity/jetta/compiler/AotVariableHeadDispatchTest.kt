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

    // D3 increments A+B: an EXPRESSION-headed (curried) application `(((curry +) 2) 3)`.
    // `curry` is defined by a space `(= …)` fact whose LHS head is itself an Expression, so
    // it never becomes a compiled function — the application escapes static dispatch and
    // reaches `JettaCallSite` via the expression-head indy wiring (increment B). The reducer
    // rewrites it by the space rule to `(+ 2 3)`, then the unified registry step (increment A)
    // computes the grounded `+` to `5`. A PARTIAL application `((curry +) 2)` matches no rule
    // (wrong arity) and must stay inert — its own normal form, per MeTTa.
    private val curried = """
        (: curry (-> (-> ${'$'}a ${'$'}b ${'$'}c) (-> ${'$'}a (-> ${'$'}b ${'$'}c))))
        (= (((curry ${'$'}f) ${'$'}x) ${'$'}y) (${'$'}f ${'$'}x ${'$'}y))
        (: curry-a (-> (-> ${'$'}a ${'$'}b ${'$'}c) ${'$'}a (-> ${'$'}b ${'$'}c)))
        (= ((curry-a ${'$'}f ${'$'}a) ${'$'}b) (${'$'}f ${'$'}a ${'$'}b))
        !(println (((curry +) 2) 3))
        !(println ((curry +) 2))
        !(println ((curry-a + 2) 3))
        !(assertEqual (((curry +) 2) 3) 5)
        !(assertEqual ((curry-a + 2) 3) 5)
        !(assertEqualToResult ((curry +) 2) (((curry +) 2)))
    """.trimIndent()

    @Test
    fun `curried application reduces to a grounded value through the unified reducer`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "jetta-curry-" + UUID.randomUUID())
        val srcDir = File(tmp, "src").apply { mkdirs() }
        val outDir = File(tmp, "out").apply { mkdirs() }
        try {
            val src = File(srcDir, "Curried.metta").apply { writeText(curried) }
            val code = Compiler(files = listOf(src.absolutePath), outputDir = outDir.absolutePath).compile()
            assertEquals(0, code, "compile should succeed")

            // No throw = the two assertEqual + assertEqualToResult held; the three println
            // lines pin the reduced full applications and the inert partial application.
            val output = runCompiled(outDir, "Curried").trim().lines().map { it.trim() }
            assertEquals("5", output[0])
            assertEquals("((curry +) 2)", output[1])
            assertEquals("5", output[2])
        } finally {
            tmp.deleteRecursively()
        }
    }

    // D3 increment C: `=`-as-reducible-head. `(= (= $x $x) T)` is a reflective-equality rule
    // (a space fact). A binary `(= a b)` in a VALUE position is dispatched to the reducer, which
    // reduces its OPERANDS applicatively — `(HumansAreMortal SocratesIsHuman)` → `SocratesIsMortal`
    // via its own `=` rule, `(+ 1 1)` / `(- 3 1)` → 2 (grounded) — and then matches the reduced
    // `(= a' a')` against the reflexive rule to yield T. A `(= a b)` whose operands stay distinct
    // (`(= SocratesIsHuman SocratesIsMortal)`) matches no rule and remains inert — its own normal
    // form (assertEqualToResult against the quoted literal).
    private val eqRedex = """
        (: Socrates Entity)
        (: Human (-> Entity Type))
        (: Mortal (-> Entity Type))
        (: HumansAreMortal (-> (Human ${'$'}t) (Mortal ${'$'}t)))
        (: SocratesIsHuman (Human Socrates))
        (: SocratesIsMortal (Mortal Socrates))
        (= (HumansAreMortal SocratesIsHuman) SocratesIsMortal)
        (: T Type)
        (= (= ${'$'}x ${'$'}x) T)
        !(assertEqual (= SocratesIsMortal (HumansAreMortal SocratesIsHuman)) T)
        !(assertEqual (= (+ 1 1) (- 3 1)) T)
        !(assertEqualToResult
           (= SocratesIsHuman SocratesIsMortal)
           ((= SocratesIsHuman SocratesIsMortal)))
    """.trimIndent()

    @Test
    fun `equality-as-redex reduces its operands and matches the reflexive rule`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "jetta-eqredex-" + UUID.randomUUID())
        val srcDir = File(tmp, "src").apply { mkdirs() }
        val outDir = File(tmp, "out").apply { mkdirs() }
        try {
            val src = File(srcDir, "EqRedex.metta").apply { writeText(eqRedex) }
            val code = Compiler(files = listOf(src.absolutePath), outputDir = outDir.absolutePath).compile()
            assertEquals(0, code, "compile should succeed")
            // No throw = both `= … → T` reductions held AND the distinct-operand `(= …)` stayed inert.
            runCompiled(outDir, "EqRedex")
        } finally {
            tmp.deleteRecursively()
        }
    }

    // D3 increment D.1: reflective `match` EXECUTED mid-reduction. The rule
    // `(= (= $type T) (match &self (: $x $type) T))` makes `(= (Mortal Socrates) T)` rewrite to
    // the runtime term `(match &self (: $x (Mortal Socrates)) T)` — which has no compiled `Match`
    // node (it was CONSTRUCTED by a rule body), so the reducer must interpret it against the live
    // space. With the `(: SocratesIsMortal (Mortal Socrates))` fact present it yields `T`; for
    // `(Mortal Plato)`, whose type is not asserted, the same `match` yields the empty bag `()`.
    // Guards the Tier-2 special-form execution: `match` runs, `empty` result threads as `()`.
    private val reflMatch = """
        (: Entity Type)
        (: Socrates Entity)
        (: Plato Entity)
        (: Human (-> Entity Type))
        (: Mortal (-> Entity Type))
        (: SocratesIsHuman (Human Socrates))
        (: SocratesIsMortal (Mortal Socrates))
        (: T Type)
        (= (= ${'$'}x ${'$'}x) T)
        (= (= ${'$'}type T)
           (match &self (: ${'$'}x ${'$'}type) T))
        !(assertEqual (= (Mortal Socrates) T) T)
        !(assertEqualToResult (= (Mortal Plato) T) ())
    """.trimIndent()

    @Test
    fun `reflective match constructed by a rule body is executed by the reducer`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "jetta-reflmatch-" + UUID.randomUUID())
        val srcDir = File(tmp, "src").apply { mkdirs() }
        val outDir = File(tmp, "out").apply { mkdirs() }
        try {
            val src = File(srcDir, "ReflMatch.metta").apply { writeText(reflMatch) }
            val code = Compiler(files = listOf(src.absolutePath), outputDir = outDir.absolutePath).compile()
            assertEquals(0, code, "compile should succeed")
            // No throw = `(= (Mortal Socrates) T)` → T (match found the `:` fact) AND
            // `(= (Mortal Plato) T)` → () (match yielded the empty bag).
            runCompiled(outDir, "ReflMatch")
        } finally {
            tmp.deleteRecursively()
        }
    }

    // D3 increment D.1b: MULTI-RULE UNION in the reducer + `if`/`==`/`empty` execution. Two
    // `(= $type T)` rules coexist — a direct-match rule and a "reasoning" rule. `(= (Mortal Plato)
    // T)` matches BOTH: the direct rule's `match` finds no `(: _ (Mortal Plato))` (→ `[]`), while
    // the reasoning rule's `match` finds `(: HumansAreMortal (-> (Human $t) (Mortal $t)))`, and the
    // constructed `(if (== (Human Plato) (Mortal Plato)) (empty) (= (Human Plato) T))` runs the
    // `==` guard (False), recurses into `(= (Human Plato) T)` → the direct rule finds
    // `(: PlatoIsHuman (Human Plato))` → `[T]`. Hyperon UNIONs the two rule bodies (`[] ∪ [T]`);
    // `firstOrNull` would drop `[T]`. Ordering mirrors d4_type_prop: the Socrates assert precedes
    // the reasoning rule (watermark keeps it single-valued `[T]`, not `[T,T]`).
    private val reflMatchReasoning = """
        (: Entity Type)
        (: Socrates Entity)
        (: Plato Entity)
        (: Human (-> Entity Type))
        (: Mortal (-> Entity Type))
        (: HumansAreMortal (-> (Human ${'$'}t) (Mortal ${'$'}t)))
        (: SocratesIsHuman (Human Socrates))
        (: PlatoIsHuman (Human Plato))
        (: SocratesIsMortal (Mortal Socrates))
        (: T Type)
        (= (= ${'$'}x ${'$'}x) T)
        (= (= ${'$'}type T)
           (match &self (: ${'$'}x ${'$'}type) T))
        !(assertEqual (= (Mortal Socrates) T) T)
        !(assertEqualToResult (= (Mortal Plato) T) ())
        (= (= ${'$'}type T)
           (match &self (: ${'$'}impl (-> ${'$'}cause ${'$'}type))
              (if (== ${'$'}cause ${'$'}type) (empty) (= ${'$'}cause T))))
        !(assertEqual (= (Mortal Plato) T) T)
        (: Sam Entity)
        !(assertEqualToResult (= (Human Sam) T) ())
    """.trimIndent()

    @Test
    fun `multi-rule union reduces reasoning rule with if-eq-empty`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "jetta-reason-" + UUID.randomUUID())
        val srcDir = File(tmp, "src").apply { mkdirs() }
        val outDir = File(tmp, "out").apply { mkdirs() }
        try {
            val src = File(srcDir, "ReflReason.metta").apply { writeText(reflMatchReasoning) }
            val code = Compiler(files = listOf(src.absolutePath), outputDir = outDir.absolutePath).compile()
            assertEquals(0, code, "compile should succeed")
            // No throw = `(= (Mortal Plato) T)` unioned both rules to `[T]` via the reasoning
            // rule's match + `if`/`==`/`empty`, and `(= (Human Sam) T)` stayed empty `()`.
            runCompiled(outDir, "ReflReason")
        } finally {
            tmp.deleteRecursively()
        }
    }

    // D3 increment (a): RELATIONAL dispatch for a free-variable argument (backward chaining).
    // `Mortal` mixes a constant clause `(= (Mortal Socrates) T)` with a wildcard clause
    // `(= (Mortal $x) (Human $x))` — the wildcard-swallow shape. Called with a FREE `$x`,
    // functional first-match dispatch fails `$x == Socrates` and is swallowed by the wildcard
    // (→ only `Plato`, via `(Human $x)` → `(= (Human Plato) T)`). The compile-time prologue
    // routes the free-var call to `reduceRelationalIfFree`, which unions over BOTH clauses by
    // unification (binding `$x = Socrates` for the constant clause, `$x = Plato` through the
    // wildcard body) and foliates the per-branch bindings, so `(ift (Mortal $x) $x)` yields the
    // full bag `[Socrates, Plato]`. Guards the relational-vs-functional fix; the narrow shape
    // trigger keeps purely-relational functions off this path.
    private val backwardChain = """
        (: Entity Type)
        (: Socrates Entity)
        (: Plato Entity)
        (: Human (-> Entity Type))
        (: Mortal (-> Entity Type))
        (: T Type)
        (= (= ${'$'}x ${'$'}x) T)
        (= (Human Plato) T)
        (= (Mortal Socrates) T)
        (= (Mortal ${'$'}x) (Human ${'$'}x))
        (: ift (-> Type ${'$'}t ${'$'}t))
        (= (ift T ${'$'}then) ${'$'}then)
        !(assertEqualToResult (ift (Mortal ${'$'}x) ${'$'}x) (Socrates Plato))
    """.trimIndent()

    @Test
    fun `free-variable argument reduces relationally over all clauses`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "jetta-backchain-" + UUID.randomUUID())
        val srcDir = File(tmp, "src").apply { mkdirs() }
        val outDir = File(tmp, "out").apply { mkdirs() }
        try {
            val src = File(srcDir, "BackChain.metta").apply { writeText(backwardChain) }
            val code = Compiler(files = listOf(src.absolutePath), outputDir = outDir.absolutePath).compile()
            assertEquals(0, code, "compile should succeed")
            // No throw = `(Mortal $x)` unioned both clauses relationally to `[T{Socrates},
            // T{Plato}]`, and `ift` foliated each binding to yield `[Socrates, Plato]`.
            runCompiled(outDir, "BackChain")
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
