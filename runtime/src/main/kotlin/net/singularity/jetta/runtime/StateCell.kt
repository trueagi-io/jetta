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
}
