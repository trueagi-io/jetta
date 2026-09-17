package net.singularity.jetta.runtime

/**
 * Runs a compiled program on a thread with a large stack.
 *
 * Minimal MeTTa is written in continuation-passing style — `chain`/`unify`/`function` thread a
 * computation through nested lambdas — and the reference interpreter runs it on its own explicit
 * stack, so recursion depth is bounded by heap. JeTTa compiles those lambdas to JVM frames, and
 * the JVM has no tail calls, so the same program is bounded by the thread's stack instead:
 * hyperon's own `he_minimalmetta.metta` divides 350000 by 5 with 70000 recursive steps and
 * overflowed a default stack at around 1000.
 *
 * A dedicated thread is the standard remedy on the JVM (Scala and Clojure do the same for deep
 * recursion) and costs one thread creation per run. The size is reserved address space, not
 * committed memory. Override with `-Djetta.stackSize=<bytes|NNNm|NNNg>`; `0` keeps the platform
 * default and runs the program on the calling thread.
 *
 * The program runs entirely inside that thread, so the thread-local [Matcher] binding stack it
 * builds is its own — nothing is shared with the caller, and the context class loader is carried
 * over so `JettaProgram.init` can still resolve the program's classes.
 */
object DeepStack {

    const val STACK_SIZE_PROPERTY = "jetta.stackSize"

    // 256 MB carries hyperon's own `he_minimalmetta` (70000 recursive steps) with room to
    // spare — it needs just over 128 MB — while keeping the time a RUNAWAY recursion takes to
    // hit the wall bounded. Reserved address space, not committed memory.
    private const val DEFAULT_STACK_BYTES = 256L * 1024 * 1024

    fun stackBytes(): Long {
        val raw = System.getProperty(STACK_SIZE_PROPERTY)?.trim()?.lowercase() ?: return DEFAULT_STACK_BYTES
        val scale = when {
            raw.endsWith("g") -> 1024L * 1024 * 1024
            raw.endsWith("m") -> 1024L * 1024
            raw.endsWith("k") -> 1024L
            else -> 1L
        }
        val digits = if (scale == 1L) raw else raw.dropLast(1)
        return digits.toLongOrNull()?.times(scale) ?: DEFAULT_STACK_BYTES
    }

    /**
     * Run [body] on a deep-stacked thread and wait for it. Anything it throws is rethrown here,
     * unwrapped, so a caller that distinguishes `AssertionError` from other failures (the test
     * runner, `assertEqual` in a compiled program) sees exactly what it would have seen from a
     * direct call.
     */
    @JvmStatic
    fun run(body: Runnable) = run(body, 0L)

    /**
     * As [run], but abandons the program after [timeoutMillis] (0 = wait forever) and throws
     * [TimeoutException]. The thread is a daemon and is left running: a JVM thread cannot be
     * stopped safely, and the caller — a test runner sweeping a corpus — needs to move on.
     */
    @JvmStatic
    fun run(body: Runnable, timeoutMillis: Long) {
        val bytes = stackBytes()
        if (bytes <= 0L && timeoutMillis <= 0L) {
            body.run()
            return
        }
        var failure: Throwable? = null
        val loader = Thread.currentThread().contextClassLoader
        val thread = Thread(null, {
            try {
                body.run()
            } catch (t: Throwable) {
                failure = t
            }
        }, "jetta-main", bytes)
        thread.contextClassLoader = loader
        // Daemon so a runaway program cannot keep the JVM alive after its caller gives up on
        // it. The normal path joins below, so this changes nothing for a program that ends.
        thread.isDaemon = true
        thread.start()
        // Uninterruptible join: the program owns this thread, and surfacing a spurious
        // InterruptedException in place of the program's own result would be a lie.
        var interrupted = false
        val deadline = if (timeoutMillis > 0L) System.currentTimeMillis() + timeoutMillis else 0L
        while (true) {
            try {
                if (deadline == 0L) {
                    thread.join()
                } else {
                    val left = deadline - System.currentTimeMillis()
                    if (left > 0) thread.join(left)
                }
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        if (thread.isAlive) throw TimeoutException(timeoutMillis)
        failure?.let { throw it }
    }

    /** The program was still running when its caller's deadline passed. */
    class TimeoutException(millis: Long) :
        RuntimeException("program did not finish within ${millis}ms")

    /**
     * Entry point the generated `main(String[])` calls: locate the program's own `__main` and run
     * it deep-stacked. Reflective because the call is emitted before the class exists, and
     * because `__main`'s descriptor varies with what the last `!`-run returns.
     *
     * A [MettaError] — a top-level `!`-run that answered `(Error …)` and so ended the program —
     * is reported as the error TERM, not as a JVM stack trace: the error is a value the program
     * computed, and the reference prints it as that run's result. We diverge from the reference on
     * the exit code only: it leaves the process status at 0, and a failed program run that a shell
     * or CI cannot notice is worse than a small divergence.
     *
     * This is the CLI path alone. A host that invokes `__main` itself (the test runner, the
     * backend's unit tests) sees the [MettaError] and decides for itself.
     */
    @JvmStatic
    fun runMain(className: String) {
        val loader = Thread.currentThread().contextClassLoader ?: DeepStack::class.java.classLoader
        val entry = Class.forName(className, true, loader).getMethod("__main")
        try {
            run {
                try {
                    entry.invoke(null)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException ?: e
                }
            }
        } catch (e: MettaError) {
            System.err.println(e.error)
            kotlin.system.exitProcess(1)
        }
    }
}
