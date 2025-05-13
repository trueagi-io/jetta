package net.singularity.jetta.compiler.frontend.ir

data class MatchBranch(
    val cond: Expression?,
    val body: Atom
)