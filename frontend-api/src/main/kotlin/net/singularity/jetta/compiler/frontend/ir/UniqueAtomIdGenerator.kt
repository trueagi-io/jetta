package net.singularity.jetta.compiler.frontend.ir

object UniqueAtomIdGenerator {
    private var counter = 0

    fun generate(): Int = counter++

    fun reset() {
        counter = 0
    }
}