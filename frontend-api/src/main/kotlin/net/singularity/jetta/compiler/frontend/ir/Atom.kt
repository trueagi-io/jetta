package net.singularity.jetta.compiler.frontend.ir

sealed interface Atom {
    var type: Atom?
    val position: SourcePosition?
}
