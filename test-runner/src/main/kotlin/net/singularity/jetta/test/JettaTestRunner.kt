package net.singularity.jetta.test

import net.singularity.jetta.compiler.Compiler
import net.singularity.jetta.compiler.logger.LogLevel
import net.singularity.jetta.runtime.JettaProgram
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files

/**
 * Orchestrates a compatibility run over a directory of `.metta` files.
 *
 * For each file the runner compiles it in isolation (fresh temp directory, fresh classloader),
 * invokes its generated entry method, classifies the outcome, and combines the result with
 * any matching entry from the supplied `xfail` map.
 *
 * The runner is intentionally sequential. The compiled programs touch static state in the
 * runtime (`JettaProgram.companion.space`, `Matcher`'s thread-local binding stack) and parallel
 * execution would require non-trivial coordination that v1 does not attempt.
 */
class JettaTestRunner {

    /**
     * Walk [directory], pick up every `.metta` file (recursively), run each one, and return
     * the aggregated [RunSummary]. Files that are referenced as `import!` targets by other
     * files in the same suite are excluded — they are modules, not standalone tests, and
     * their atoms participate via their importers. The walk order is sorted by absolute
     * path so reports are stable across invocations.
     *
     * @param directory the suite root; an exception is thrown if it does not exist or is not a directory.
     * @param xfail map from suite-relative path to the `xfail` entry for that test, if any.
     */
    fun run(directory: File, xfail: Map<String, XfailEntry>): RunSummary {
        require(directory.exists() && directory.isDirectory) {
            "Test directory does not exist or is not a directory: ${directory.absolutePath}"
        }

        val allFiles = directory
            .walkTopDown()
            .filter { it.isFile && it.extension == "metta" }
            .sortedBy { it.absolutePath }
            .toList()

        val moduleOnlyNames = ImportGraph.importedNames(allFiles)
        val entryFiles = allFiles.filter { it.nameWithoutExtension !in moduleOnlyNames }

        val entries = entryFiles.map { runSingle(it, directory, xfail) }
        return RunSummary(entries)
    }

    /**
     * Compile and execute one source file, returning a [ReportEntry].
     *
     * Compiles into a per-test temp directory which is removed in `finally`. While compilation
     * and execution run, `System.out` / `System.err` are redirected into per-test buffers so
     * that any printf-style output from the test (or noisy compiler logging) ends up in the
     * report rather than the runner's own console.
     *
     * The compile call is wrapped in `runCatching` separately from [invokeMain]; this matters
     * because a `Throwable` from `compile()` is a compiler-level problem (`COMPILE_FAIL`) while
     * a `Throwable` from the running program's `__main` is a runtime problem (`RUN_EXCEPTION`).
     * Lumping them under one `try/catch` would misclassify VerifyError-style failures.
     */
    private fun runSingle(sourceFile: File, root: File, xfail: Map<String, XfailEntry>): ReportEntry {
        val relative = sourceFile.relativeTo(root).path
        val xfailEntry = xfail[relative]
        val outputDir = Files.createTempDirectory("jetta-test-").toFile()

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err

        var status: TestStatus
        var message: String

        try {
            System.setOut(PrintStream(stdout))
            System.setErr(PrintStream(stderr))

            val compiler = Compiler(
                files = listOf(sourceFile.absolutePath),
                outputDir = outputDir.absolutePath,
                logLevel = LogLevel.ERROR,
            )
            val compileResult = runCatching { compiler.compile() }
            val outcome: Pair<TestStatus, String> = when {
                compileResult.isFailure -> {
                    val t = compileResult.exceptionOrNull()!!
                    Pair(
                        TestStatus.COMPILE_FAIL,
                        "Compiler crashed: ${t::class.qualifiedName}: ${t.message ?: ""}",
                    )
                }
                compileResult.getOrThrow() != 0 -> {
                    Pair(TestStatus.COMPILE_FAIL, "Compiler exit code ${compileResult.getOrThrow()}")
                }
                else -> invokeMain(outputDir, sourceFile.nameWithoutExtension)
            }
            status = outcome.first
            message = outcome.second
        } catch (t: Throwable) {
            // Anything outside the compile/run boundary — e.g. an unexpected I/O failure
            // when redirecting streams. We still want a report entry rather than the
            // whole run aborting.
            status = TestStatus.RUN_EXCEPTION
            message = "Test runner internal error: ${t::class.qualifiedName}: ${t.message}"
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            try {
                outputDir.deleteRecursively()
            } catch (_: Throwable) {
                // Best-effort cleanup; never fail the run on temp-dir removal issues.
            }
        }

        val combinedOutput = buildString {
            val o = stdout.toString()
            val e = stderr.toString()
            if (o.isNotEmpty()) append(o)
            if (e.isNotEmpty()) {
                if (isNotEmpty()) append("\n--- stderr ---\n")
                append(e)
            }
        }

        val classification = classify(status, xfailEntry)
        return ReportEntry(
            file = relative,
            status = status,
            classification = classification,
            message = message,
            output = combinedOutput,
            xfailReason = xfailEntry?.let { "${it.expectedStatus}:${it.reasonCode}" },
        )
    }

    /**
     * Load the generated entry class via a fresh [URLClassLoader], find a parameterless
     * `main` (or `__main`) method, and invoke it. Returns the resulting [TestStatus] and
     * a short human-readable message.
     *
     * The class is loaded with a child classloader off [outputDir]; once the function
     * returns, the loader becomes unreachable and its loaded classes can be unloaded by GC.
     * This is what gives the runner test isolation across files: each test sees a fresh
     * static state of its own generated class.
     *
     * `JettaProgram.setDataDir` is called before invocation because the generated `__main`
     * begins with `JettaProgram.init(<className>)`, which loads `<className>.manifest.json`
     * relative to that data directory.
     */
    private fun invokeMain(outputDir: File, programName: String): Pair<TestStatus, String> {
        val classLoader = URLClassLoader(arrayOf(outputDir.toURI().toURL()), javaClass.classLoader)
        val mainClass = try {
            classLoader.loadClass(programName)
        } catch (e: ClassNotFoundException) {
            return TestStatus.RUN_EXCEPTION to "Generated class not found: $programName"
        }
        // Prefer a 0-arg `main`, then `__main`, then JVM-style `main(String[])` as a last
        // resort. Some JeTTa programs without `!`-runs may not have any of these (the
        // compiler skips main generation when there is nothing to run); for those we
        // surface a clear "no entry point" message instead of silently passing.
        val method = mainClass.methods.firstOrNull { it.name == "main" && it.parameterCount == 0 }
            ?: mainClass.methods.firstOrNull { it.name == "__main" && it.parameterCount == 0 }
            ?: mainClass.methods.firstOrNull {
                it.name == "main" && it.parameterCount == 1 && it.parameterTypes[0] == Array<String>::class.java
            }
            ?: return TestStatus.RUN_EXCEPTION to "No main/__main entry point on class $programName"

        // Generated __main calls JettaProgram.init(className) which resolves
        // <className>.manifest.json in dataDir; point it at our temp output dir.
        JettaProgram.setDataDir(outputDir.toPath())

        // Make the program's own ClassLoader the thread context loader so JettaProgram.init
        // can resolve the compiled classes named in `<program>.jctx` for variable-head
        // dispatch (`findStatic`). Under the CLI everything is on one `-cp`, but here the
        // program lives in a per-run URLClassLoader that the app loader can't see.
        val savedContextLoader = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = classLoader
        return try {
            if (method.parameterCount == 0) method.invoke(null) else method.invoke(null, emptyArray<String>())
            TestStatus.PASS to "OK"
        } catch (e: InvocationTargetException) {
            // Reflection wraps the underlying throwable; unwrap once so AssertionError is
            // distinguishable from arbitrary RuntimeException.
            when (val cause = e.targetException ?: e) {
                is AssertionError -> TestStatus.ASSERT_FAIL to (cause.message ?: cause.toString())
                else -> TestStatus.RUN_EXCEPTION to "${cause::class.qualifiedName}: ${cause.message ?: ""}"
            }
        } catch (t: Throwable) {
            // Class-loading or reflection-setup failures land here — e.g. JVM verifier
            // rejecting the bytecode of a buggy compile that returned exit 0.
            TestStatus.RUN_EXCEPTION to "${t::class.qualifiedName}: ${t.message ?: ""}"
        } finally {
            Thread.currentThread().contextClassLoader = savedContextLoader
        }
    }
}
