package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.BoundAtom

@Suppress("unused")
fun <T, R> simpleMap(f: java.util.function.Function<T, R>, list: List<T>): List<R> {
    val result = ArrayList<R>(list.size)
    for (element in list) {
        Matcher.push()
        val unwrapped = if (element is BoundAtom) {
            Matcher.getBindings().putAll(element.bindings)
            @Suppress("UNCHECKED_CAST")
            element.atom as T
        } else element
        result.add(f.apply(unwrapped))
        Matcher.pop()
    }
    return result
}

@Suppress("unused")
fun <T, R> simpleFlatMap(f: java.util.function.Function<T, java.util.List<R>>, list: List<T>): List<R> {
    val result = ArrayList<R>(list.size)
    for (element in list) {
        Matcher.push()
        val unwrapped = if (element is BoundAtom) {
            Matcher.getBindings().putAll(element.bindings)
            @Suppress("UNCHECKED_CAST")
            element.atom as T
        } else element
        result.addAll(f.apply(unwrapped))
        Matcher.pop()
    }
    return result
}
