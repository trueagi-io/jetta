package net.singularity.jetta.compiler.frontend.ir

interface FunctionLike : Atom {
    val params: List<Variable>
    var arrowType: ArrowType?
    val body: Atom
    val returnType: Atom?
}