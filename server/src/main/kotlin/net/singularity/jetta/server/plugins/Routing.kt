package net.singularity.jetta.server.plugins

import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import net.singularity.jetta.server.models.toDto
import net.singularity.jetta.server.services.ReplService
import java.util.*

fun Application.configureRouting(replService: ReplService) {
    routing {
        post("/contexts") {
            val contextId = replService.createContextId()
            call.respond(HttpStatusCode.OK, contextId.toString())
        }
        post("/contexts/{contextId}") {
            val contextId = UUID.fromString(call.parameters["contextId"]!!)
            val code = call.receiveText()
            val result = replService.eval(contextId, code).toDto()
            call.respond(HttpStatusCode.OK, result)
        }
    }
}
