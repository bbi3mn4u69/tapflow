package com.tapflow.api

import com.tapflow.api.auth.AuthService
import com.tapflow.api.auth.RateLimits
import com.tapflow.api.auth.authRoutes
import com.tapflow.api.auth.configureAuthentication
import com.tapflow.api.auth.configureRequestSecurity
import com.tapflow.api.cards.CardService
import com.tapflow.api.cards.cardRoutes
import com.tapflow.api.common.DomainError
import com.tapflow.api.config.AppConfig
import com.tapflow.api.config.AuthConfig
import com.tapflow.api.config.CardConfig
import com.tapflow.api.config.configurePlugins
import com.tapflow.api.db.Database
import com.tapflow.api.merchants.MerchantAccess
import com.tapflow.api.merchants.ReportsService
import com.tapflow.api.merchants.operationsRoutes
import com.tapflow.api.payments.PaymentService
import com.tapflow.api.payments.RefundService
import com.tapflow.api.payments.paymentRoutes
import com.tapflow.api.terminals.TerminalService
import com.tapflow.api.terminals.terminalRoutes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.UUID

fun main() {
    val config = AppConfig.fromEnvironment(System.getenv())
    val authConfig = AuthConfig.fromEnvironment(System.getenv())
    val cards = CardConfig.fromEnvironment(System.getenv())
    Database.open(config.database).use { database ->
        val auth = AuthService(database, authConfig)
        embeddedServer(Netty, host = config.host, port = config.port) {
                module(
                    database,
                    auth = auth,
                    cardConfig = cards,
                    terminals = TerminalService(database, authConfig.signingSecret),
                )
            }
            .start(wait = true)
    }
}

fun Application.module(
    database: Database? = null,
    nextRequestId: () -> UUID = UUID::randomUUID,
    auth: AuthService? = null,
    limits: RateLimits = RateLimits(),
    cardConfig: CardConfig = CardConfig(),
    terminals: TerminalService? = null,
    payments: PaymentService? = null,
) {
    configurePlugins(nextRequestId)
    if (auth != null) configureAuthentication(auth)
    configureRequestSecurity(limits)
    val contract =
        checkNotNull(Application::class.java.getResourceAsStream("/contracts/openapi.yaml")) {
                "The packaged API contract is missing"
            }
            .bufferedReader()
            .use { it.readText() }
    routing {
        if (auth != null && database != null) authRoutes(auth, MerchantAccess(database), limits)
        if (auth != null && database != null) cardRoutes(CardService(database, cardConfig), limits)
        if (auth != null && terminals != null) terminalRoutes(terminals)
        if (auth != null && database != null)
            paymentRoutes(payments ?: PaymentService(database), limits)
        if (auth != null && database != null)
            operationsRoutes(RefundService(database), ReportsService(database), limits)
        get("/health/live") {
            call.respond(HealthStatus("alive"))
        }
        get("/api/v1/status") {
            call.respond(SystemStatus())
        }
        get("/health/ready") {
            if (database == null || !database.ready()) {
                throw DomainError(
                    HttpStatusCode.ServiceUnavailable,
                    "SERVICE_UNAVAILABLE",
                    "The database is not ready.",
                )
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
    val phase: String = "merchant-operations",
    val demo: Boolean = true,
)

@kotlinx.serialization.Serializable
data class HealthStatus(val status: String, val demo: Boolean = true)
