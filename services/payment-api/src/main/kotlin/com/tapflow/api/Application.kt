package com.tapflow.api

import io.ktor.server.application.Application
import com.tapflow.api.common.DomainError
import com.tapflow.api.config.AppConfig
import com.tapflow.api.config.configurePlugins
import com.tapflow.api.db.Database
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.UUID

fun main() {
    val config = AppConfig.fromEnvironment(System.getenv())
    Database.open(config.database).use { database ->
        embeddedServer(Netty, host = config.host, port = config.port) { module(database) }.start(wait = true)
    }
}

fun Application.module(database: Database? = null, nextRequestId: () -> UUID = UUID::randomUUID) {
    configurePlugins(nextRequestId)
    val contract = checkNotNull(Application::class.java.getResourceAsStream("/contracts/openapi.yaml")) {
        "The packaged API contract is missing"
    }.bufferedReader().use { it.readText() }
    routing {
        get("/health/live") {
            call.respond(HealthStatus("alive"))
        }
        get("/api/v1/status") {
            call.respond(SystemStatus())
        }
        get("/health/ready") {
            if (database == null || !database.ready()) {
                throw DomainError(HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "The database is not ready.")
            }
            call.respond(HealthStatus("ready"))
        }
        get("/api/v1/openapi.yaml") {
            call.respondText(contract, ContentType.parse("application/yaml"))
        }
    }
}

@kotlinx.serialization.Serializable
data class SystemStatus(
    val service: String = "tapflow-api",
    val phase: String = "foundation",
    val demo: Boolean = true,
)

@kotlinx.serialization.Serializable
data class HealthStatus(val status: String, val demo: Boolean = true)
