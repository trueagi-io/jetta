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

    /**
     * Bytes of stack per MeTTa call level, for turning a `max-stack-depth` into a thread stack
     * size. MEASURED at ~233 bytes/level on this compiler's output (a small recursive function
     * over an `Int`: 8000 levels fit in 2 MB and 40000 in 8 MB, 10000 overflowed 2 MB) and then
     * given a 4x margin, because a frame grows with the function's locals and the error must fall
     * on the generous side: over-provisioning only lets a runaway recursion run a little longer,
     * while under-provisioning would cut short a recursion the program asked to allow.
     */
    private const val BYTES_PER_CALL = 1024L

    /**
     * The floor a sized stack is clamped to. A bound of a few hundred calls would compute a stack
     * too small to load a space or run the reducer at all, and the program would overflow inside
     * the runtime rather than in its own recursion. A trivial program was measured to run in
     * 128 KB; this leaves room for the reflective paths on top of that.
     */
    private const val MIN_SIZED_BYTES = 512L * 1024

    /**
     * The stack size for a program that asked for [maxCalls] levels of recursion, clamped to
     * [MIN_SIZED_BYTES] below and to the default above — the pragma exists to BOUND a program's
     * recursion, never to hand it more stack than a program that asked for nothing.
     *
     * An explicit `-Djetta.stackSize` still wins: it is the operator's override of exactly this
     * number, and a program's own pragma should not defeat it.
     */
    fun stackBytesFor(maxCalls: Int): Long {
        if (System.getProperty(STACK_SIZE_PROPERTY) != null) return stackBytes()
        val wanted = maxCalls.toLong() * BYTES_PER_CALL
        return wanted.coerceIn(MIN_SIZED_BYTES, DEFAULT_STACK_BYTES)
    }

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
    fun run(body: Runnable, timeoutMillis: Long) = run(body, timeoutMillis, stackBytes())

    /**
     * As [run], but on a stack of exactly [bytes] — the size a program's own `max-stack-depth`
     * computed, rather than the default this JVM would pick.
     */
    @JvmStatic
    fun run(body: Runnable, timeoutMillis: Long, bytes: Long) {
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
    fun runMain(className: String) = runMain(className, stackBytes())

    /**
     * As [runMain], but for a program whose `(pragma! max-stack-depth N)` is a literal the
     * compiler could read: the thread's stack is sized for N calls, so the JVM enforces the bound
     * with no per-call counter, and `Errors.stackOverflow` turns the hit into the reference's
     * `(Error <run> StackOverflow)`. See [stackBytesFor] for the unit conversion and its margins.
     */
    @JvmStatic
    fun runMain(className: String, maxCalls: Int) = runMain(className, stackBytesFor(maxCalls))

    private fun runMain(className: String, bytes: Long) {
        val loader = Thread.currentThread().contextClassLoader ?: DeepStack::class.java.classLoader
        val entry = Class.forName(className, true, loader).getMethod("__main")
        try {
            run({
                try {
                    entry.invoke(null)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException ?: e
                }
            }, 0L, bytes)
        } catch (e: MettaError) {
            System.err.println(e.error)
            kotlin.system.exitProcess(1)
        }
    }
}
