package net.singularity.jetta.runtime

import net.singularity.jetta.runtime.functions.JettaFunction
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
    fun generate(func: JettaFunction, start: Double, end: Double, step: Double): List<Double> {
        val result = mutableListOf<Double>()
        var x = start
        while (x < end) {
            result += func.apply(arrayOf<Any?>(x)) as Double
            x += step
        }
        return result
    }
}
