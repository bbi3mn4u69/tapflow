package com.tapflow.api

import io.ktor.server.application.Application
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
    embeddedServer(
        Netty,
        host = System.getenv("API_HOST") ?: "127.0.0.1",
        port = (System.getenv("API_PORT") ?: "8080").toInt(),
        module = Application::module,
    ).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json(kotlinx.serialization.json.Json { encodeDefaults = true }) }
    routing {
        get("/health/live") {
            call.respond(HealthStatus("alive"))
        }
        get("/api/v1/status") {
            call.respond(SystemStatus())
        }
        get("/health/ready") {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthStatus("persistence-not-configured"))
        }
    }
}

@kotlinx.serialization.Serializable
data class SystemStatus(
    val service: String = "tapflow-api",
    val phase: String = "bootstrap",
    val demo: Boolean = true,
)

@kotlinx.serialization.Serializable
data class HealthStatus(val status: String, val demo: Boolean = true)
