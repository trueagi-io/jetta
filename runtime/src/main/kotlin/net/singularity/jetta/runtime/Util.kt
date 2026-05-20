package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.BoundAtom
import net.singularity.jetta.runtime.functions.JettaFunction

@Suppress("unused")
fun simpleMap(f: JettaFunction, list: List<Any?>): List<Any?> {
    val result = ArrayList<Any?>(list.size)
    for (element in list) {
        Matcher.push()
        val unwrapped = if (element is BoundAtom) {
            Matcher.getBindings().putAll(element.bindings)
            element.atom
        } else element
        result.add(f.apply(arrayOf(unwrapped)))
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
            Matcher.getBindings().putAll(element.bindings)
            element.atom
        } else element
        @Suppress("UNCHECKED_CAST")
        result.addAll(f.apply(arrayOf(unwrapped)) as List<Any?>)
        Matcher.pop()
    }
    return result
}
