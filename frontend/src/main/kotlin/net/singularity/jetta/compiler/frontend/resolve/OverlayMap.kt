package net.singularity.jetta.compiler.frontend.resolve

/**
 * Copy-on-write map view: reads fall through to a shared, read-only [parent];
 * writes are captured in a private local layer and never touch [parent].
 *
 * This is the substrate for forking a resolved [Context] for JIT-eval: a fork
 * shares the AOT compiler's (large) durable symbol tables by reference and layers
 * only its own synthetic additions (the `__evalN` function) on top — O(additions)
 * per fork instead of O(whole table). The COW discipline is the correctness point:
 * concurrent / repeated evals each get an independent local layer, so none can
 * stomp the shared AOT tables or each other.
 *
 * Not thread-safe for concurrent writes to the SAME instance; distinct forks hold
 * distinct instances and only read the shared [parent], which must be effectively
 * immutable after AOT.
 */
class OverlayMap<K, V>(private val parent: Map<K, V>) : AbstractMutableMap<K, V>() {
    private val local = HashMap<K, V>()

    override fun get(key: K): V? = if (local.containsKey(key)) local[key] else parent[key]

    override fun containsKey(key: K): Boolean = local.containsKey(key) || parent.containsKey(key)

    override fun put(key: K, value: V): V? {
        val previous = get(key)
        local[key] = value
        return previous
    }

    // Removal applies only to the local layer — the shared parent is read-only.
    override fun remove(key: K): V? = if (local.containsKey(key)) local.remove(key) else null

    override val size: Int get() = merged().size

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = merged().entries

    /** Snapshot of parent ∪ local (local wins). Used for iteration only. */
    private fun merged(): LinkedHashMap<K, V> {
        val out = LinkedHashMap<K, V>(parent)
        out.putAll(local)
        return out
    }
}
