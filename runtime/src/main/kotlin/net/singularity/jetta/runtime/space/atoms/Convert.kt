package net.singularity.jetta.runtime.space.atoms

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.compiler.frontend.ir.Grounded
import net.singularity.jetta.compiler.frontend.ir.Symbol

fun Atom.toSAtom(): SAtom = when (this) {
    is Symbol -> SSymbol(this.name)
    is Expression -> SExpression(this.atoms.map { it.toSAtom() })
    is Grounded<*> -> SGrounded(this.value)
    else -> throw IllegalArgumentException("Unsupported atom type: ${this::class.simpleName}")
}

fun SAtom.toAtom(): Atom = when (this) {
    is SSymbol -> Symbol(this.name)
    is SExpression -> Expression(this.atoms.map { it.toAtom() })
    is SGrounded<*> -> Grounded(this.value)
    else -> throw IllegalArgumentException("Unsupported SAtom type: ${this::class.simpleName}")
}