package net.singularity.jetta.runtime.functions

interface Function4<T1, T2, T3, T4, R> {
    fun apply(x1: T1, x2: T2, x3: T3, x4: T4): R
}