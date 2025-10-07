package net.singularity.jetta.runtime.space

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression

class SpaceImpl : Space {
    private val store = mutableListOf<Expression>()

    override fun put(expression: Expression) {
        store.add(expression)
    }

    override fun mkIndex(patterns: List<Expression>) {
        TODO("Not yet implemented")
    }

    override fun contains(id: Int): Boolean {
        return store.find { it.id == id } != null
    }

    override fun match(
        src: Expression,
        dst: Atom
    ): List<Expression> {
        return listOf()
    }

    companion object {
        private val instance = SpaceImpl()

        @JvmStatic
        fun getInstance(): Space = instance
    }
}