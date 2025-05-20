package net.singularity.jetta.runtime

import net.singularity.jetta.runtime.functions.Function1
import kotlin.random.Random

object Random {
    var random = Random(System.nanoTime())

    @JvmStatic
    fun seed(seed: Long) {
        random = Random(seed)
    }

    @JvmStatic
    fun random(): Double = random.nextDouble()

    @JvmStatic
    fun generate(func: Function1<Double, Double>, start: Double, end: Double, step: Double): List<Double> {
        val result = mutableListOf<Double>()
        var x = start
        while (x < end) {
            result += func.apply(x)
            x += step
        }
        return result
    }
}