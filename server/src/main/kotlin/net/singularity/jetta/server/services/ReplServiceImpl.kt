package net.singularity.jetta.server.services

import net.singularity.jetta.repl.EvalResult
import net.singularity.jetta.repl.Repl
import net.singularity.jetta.repl.ReplImpl
import net.singularity.jetta.server.models.ContextId

class ReplServiceImpl : ReplService {
    private val contexts = mutableMapOf<ContextId, Repl>()

    override fun createContextId(): ContextId = ContextId.randomUUID()

    override fun eval(contextId: ContextId, code: String): EvalResult {
        val repl = contexts.getOrPut(contextId) { ReplImpl() }
        return repl.eval(code)
    }
}