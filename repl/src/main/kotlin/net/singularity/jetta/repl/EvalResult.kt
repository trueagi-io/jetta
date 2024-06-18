package net.singularity.jetta.repl

data class EvalResult(val result: Any?, val messages: List<String>, val isSuccess: Boolean)