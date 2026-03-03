package net.singularity.jetta.runtime.space

import net.singularity.jetta.runtime.space.atoms.SAtom


class HashMapBindingsImpl : Bindings {
    private val map = HashMap<String, SAtom>()

    override operator fun set(name: String, value: SAtom) {
        map[name] = value
    }

    override operator fun get(name: String): SAtom? {
        return map[name]
    }

    override fun size(): Int = map.size

    override fun clear() {
        map.clear()
    }
}