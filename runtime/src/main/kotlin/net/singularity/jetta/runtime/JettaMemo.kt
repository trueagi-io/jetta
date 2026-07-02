package net.singularity.jetta.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-function memoization tables for AOT auto-tabled functions.
 *
 * A tabled function `f` compiles to a wrapper `f` that keys a table on its (boxed)
 * argument list and delegates to `f$impl` only on a miss (see the Generator's memo-wrapper
 * emission). This turns naive exponential recursion (e.g. `fib`) into O(distinct-args).
 *
 * Soundness is decided at COMPILE time and only on the AOT (closed-world) path: the
 * compiler tables a function only when it is pure, deterministic and state-independent
 * (no `match`/IO/mutation, primitive args + result, transitively pure callees). Here we
 * just store and look up. Cached values are non-null boxed primitives, so a `null` lookup
 * result unambiguously means "miss". Tables live for the JVM process (one `jetta` run).
 */
object JettaMemo {
    private val tables = ConcurrentHashMap<String, ConcurrentHashMap<Any?, Any?>>()

    @JvmStatic
    fun lookup(fn: String, key: Any?): Any? = tables.getOrPut(fn) { ConcurrentHashMap() }[key]

    @JvmStatic
    fun store(fn: String, key: Any?, value: Any?) {
        tables.getOrPut(fn) { ConcurrentHashMap() }[key] = value
    }
}
