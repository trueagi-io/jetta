package net.singularity.jetta.runtime.space

import net.singularity.jetta.runtime.space.atoms.SAtom


interface Bindings {
    operator fun set(name: String, value: SAtom)

    operator fun get(name: String): SAtom?

    fun size(): Int

    fun clear()
}