package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.runtime.functions.JettaFunction

// Materialise a branch result against the bindings its branch installed AND foliate those
// bindings onto the result so they survive to downstream map?/flat-map? stages. See
// [Matcher.foliate] for the full rationale (per-branch binding isolation vs pop's last-wins
// upward merge). No-op — plain value returned — when the branch bound nothing (hot path).
private fun materialize(value: Any?): Any? = Matcher.foliate(value)

@Suppress("unused")
fun simpleMap(f: JettaFunction, list: List<Any?>): List<Any?> {
    val result = ArrayList<Any?>(list.size)
    for (element in list) {
        Matcher.push()
        val unwrapped = if (element is BoundAtom) {
            Matcher.installBindings(element.bindings)
            element.atom
        } else element
        result.add(materialize(f.apply(arrayOf(unwrapped))))
        Matcher.pop()
    }
    return result
}

@Suppress("unused")
fun simpleFlatMap(f: JettaFunction, list: List<Any?>): List<Any?> {
    val result = ArrayList<Any?>(list.size)
    for (element in list) {
        Matcher.push()
        val unwrapped = if (element is BoundAtom) {
            Matcher.installBindings(element.bindings)
            element.atom
        } else element
        // A branch that does not reduce to any value yields no results — it contributes
        // nothing to the flattened bag (MeTTa non-determinism: an empty branch is dropped,
        // not an error). A JettaFunction whose compiled body has no applicable clause hands
        // back null here; treat that as the empty list. Tolerating it in the combinator (a)
        // matches the semantics and (b) avoids a thrown NullPointerException on the hot path —
        // exceptions must never be part of normal control flow.
        @Suppress("UNCHECKED_CAST")
        (f.apply(arrayOf(unwrapped)) as List<Any?>?)?.forEach { result.add(materialize(it)) }
        Matcher.pop()
    }
    return result
}
