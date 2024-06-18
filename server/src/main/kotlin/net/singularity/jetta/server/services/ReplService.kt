package net.singularity.jetta.server.services

import net.singularity.jetta.repl.EvalResult
import net.singularity.jetta.server.models.ContextId

interface ReplService {
    fun createContextId(): ContextId

    fun eval(contextId: ContextId, code: String): EvalResult
}