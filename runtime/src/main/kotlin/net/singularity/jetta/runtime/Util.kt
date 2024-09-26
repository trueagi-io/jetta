package net.singularity.jetta.runtime

fun <T, R> simpleMap(f: java.util.function.Function<T, R>, list: List<T>): List<R> {
    val result = ArrayList<R>(list.size)
    for(i in list.indices) {
        result.add(f.apply(list[i]))
    }
    return result
}

fun <T, R> simpleFlatMap(f: java.util.function.Function<T, java.util.List<R>>, list: List<T>): List<R> {
    val result = ArrayList<R>(list.size)
    for(i in list.indices) {
        result.addAll(f.apply(list[i]))
    }
    return result
}

