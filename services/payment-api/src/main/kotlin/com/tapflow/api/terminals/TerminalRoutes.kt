package com.tapflow.api.terminals

import com.tapflow.api.auth.*
import com.tapflow.api.common.DomainError
import io.ktor.http.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.terminalRoutes(terminals: TerminalService) {
    authenticate("access") {
        route("/api/v1/merchants/{merchantId}/terminals") {
            post("/pairing-codes") {
                val key =
                    call.request.header("Idempotency-Key")?.canonicalUuid()?.takeIf {
                        it.version() == 4 && it.variant() == 2
                    } ?: invalid()
                if (call.request.contentType().withoutParameters() != ContentType.Application.Json)
                    invalid()
                val result =
                    terminals.create(
                        call.identity(),
                        call.parameters["merchantId"]?.canonicalUuid() ?: invalid(),
                        call.receive(),
                        key,
                        call.requestId(),
                    )
                call.respond(HttpStatusCode.fromValue(result.status), result.body)
            }
            get {
                val limit =
                    call.request.queryParameters["limit"]?.toIntOrNull()
                        ?: if (call.request.queryParameters.contains("limit")) invalid() else 25
                call.respond(
                    terminals.list(
                        call.identity(),
                        call.parameters["merchantId"]?.canonicalUuid() ?: invalid(),
                        limit,
                        call.request.queryParameters["cursor"],
                    )
                )
            }
            post("/{terminalId}/disable") {
                call.respond(
                    terminals.disable(
                        call.identity(),
                        call.parameters["merchantId"]?.canonicalUuid() ?: invalid(),
                        call.parameters["terminalId"]?.canonicalUuid() ?: invalid(),
                        call.requestId(),
                    )
                )
            }
        }
        post("/api/v1/terminals/pair") {
            try {
                val key =
                    call.request.header("Idempotency-Key")?.canonicalUuid()?.takeIf {
                        it.version() == 4 && it.variant() == 2
                    } ?: invalid()
                if (call.request.contentType().withoutParameters() != ContentType.Application.Json)
                    invalid()
                val result = terminals.pair(call.identity(), call.receive(), key, call.requestId())
                call.respond(HttpStatusCode.fromValue(result.status), result.body)
            } catch (failure: DomainError) {
                call.application.environment.log.info(
                    "terminal.pair.rejected requestId={}",
                    call.requestId(),
                )
                throw failure
            }
        }
    }
}
