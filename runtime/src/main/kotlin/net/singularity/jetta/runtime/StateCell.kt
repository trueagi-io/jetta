package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom

/**
 * A mutable state cell — the thing `new-state` creates and `change-state!` mutates. Wrapped
 * in a `Grounded` so it is a first-class atom that can be bound to a token via `bind!` and
 * read with `get-state`. Reference identity matters: two `new-state` calls yield distinct
 * cells even for the same initial value.
 */
class StateCell(@Volatile var value: Atom) {
    override fun toString(): String = "(State $value)"

    /**
     * Two states are equal when they hold equal values, even when they are distinct cells —
     * hyperon's documented behaviour (`(assertEqual (get-token) (new-state (A B)))` holds, and a
     * `match` pattern carrying one state atom finds a space atom carrying another with the same
     * content). Cell IDENTITY still decides what `change-state!` mutates; only comparison is by
     * value.
     */
    override fun equals(other: Any?): Boolean = this === other || (other is StateCell && other.value == value)

    /**
     * Deliberately constant. [value] is mutable, so hashing it would move a state atom between
     * buckets of a live space index the moment `change-state!` ran. States are few, so the
     * collisions cost nothing.
     */
    override fun hashCode(): Int = STATE_HASH

    private companion object {
        private const val STATE_HASH = 0x57A7E
    }
}
