package net.singularity.jetta.runtime.functions

fun interface JettaFunction {
    fun apply(args: Array<Any?>): Any?
}
