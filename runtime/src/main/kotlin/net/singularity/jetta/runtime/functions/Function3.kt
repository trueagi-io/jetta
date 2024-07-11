package net.singularity.jetta.runtime.functions

interface Function3<T1, T2, T3, R> {
    fun apply(x1: T1, x2: T2, x3: T3): R
}