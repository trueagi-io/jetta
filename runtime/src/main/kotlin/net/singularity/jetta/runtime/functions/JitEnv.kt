package net.singularity.jetta.runtime.functions

import net.singularity.jetta.compiler.frontend.resolve.Context
import net.singularity.jetta.runtime.space.Space

/**
 * A same-JVM JIT-eval environment, handed to [JettaJit] so an `eval` can run against
 * the REAL resolved program instead of a system-only context:
 * - [context] — the live AOT-resolved [Context] to [Context.fork] for resolving eval'd
 *   code (so user functions resolve, with their owner classes → `INVOKESTATIC` links);
 * - [classLoader] — the loader holding the program's compiled classes, so the eval'd
 *   synthetic class can link against them at load time;
 * - [space] — the live COMPILE-TIME space holding the program's `(= …)` rules/facts.
 *   In same-JVM execution there is no `.jtsf` round-trip, so [JettaProgram.init]
 *   registers THIS space into the runtime registry (instead of a fresh empty one), and
 *   it is the space eval'd `match`/dispatch routes to.
 */
class JitEnv(val context: Context, val classLoader: ClassLoader, val space: Space)

/**
 * Holds the [JitEnv] active for the current top-level run. The producer of the
 * compiled program (the REPL today; an in-process compile-and-run later) installs it
 * around the `__main` invocation and clears it afterwards, so [JettaJit.eval] — called
 * from within the run — forks the real environment.
 *
 * Cross-JVM (`jettac` then a fresh `jetta`) has no live env: nothing is installed and
 * [JettaJit] falls back to a system-only context. Persisting the durable overlay to a
 * `.jctx` artifact for that case is a later slice.
 *
 * Single active env at a time (one running program per JVM in this slice); `@Volatile`
 * gives visibility, not multi-writer safety.
 */
object JitEnvRegistry {
    @Volatile
    private var current: JitEnv? = null

    @JvmStatic
    fun install(env: JitEnv) {
        current = env
    }

    @JvmStatic
    fun clear() {
        current = null
    }

    @JvmStatic
    fun current(): JitEnv? = current
}
