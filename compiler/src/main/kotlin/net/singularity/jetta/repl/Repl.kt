package net.singularity.jetta.repl

interface Repl {
    fun eval(code: String): EvalResult
}