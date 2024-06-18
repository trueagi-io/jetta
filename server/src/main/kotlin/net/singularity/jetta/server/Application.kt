package net.singularity.jetta.server

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import net.singularity.jetta.server.plugins.configureRouting
import net.singularity.jetta.server.plugins.configureSecurity
import net.singularity.jetta.server.services.ReplServiceImpl

fun main() {
    embeddedServer(Netty, port = 9090, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    val replService = ReplServiceImpl()
    configureSecurity()
    configureRouting(replService)
}
