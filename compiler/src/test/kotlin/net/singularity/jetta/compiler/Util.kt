package net.singularity.jetta.compiler

fun withCompiler(scope: CompilerSupport.() -> Unit) {
    val support = CompilerSupport()
    support.scope()
    support.dispose()
}