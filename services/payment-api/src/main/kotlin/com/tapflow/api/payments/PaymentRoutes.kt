package com.tapflow.api.payments

import com.tapflow.api.auth.*
import io.ktor.http.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import java.util.UUID

fun Route.paymentRoutes(payments: PaymentService, limits: RateLimits) {
    authenticate("access") {
        route("/api/v1/merchants/{merchantId}") {
            route("/payment-intents") {
                post {
                    val identity = call.identity()
                    limits.check("payments:write:${identity.userId}", 60, 60)
                    if (
                        call.request.contentType().withoutParameters() !=
                            ContentType.Application.Json
                    )
                        invalid()
                    val response =
                        payments.create(
                            identity,
                            call.parameters["merchantId"]?.canonicalUuid() ?: invalid(),
                            call.receive(),
                            call.paymentKey(),
                            call.requestId(),
                        )
                    call.respond(HttpStatusCode.fromValue(response.status), response.body)
                }
                get("/{intentId}") {
                    call.respond(
                        payments.get(
                            call.identity(),
                            call.parameters["merchantId"]?.canonicalUuid() ?: invalid(),
                            call.parameters["intentId"]?.canonicalUuid() ?: invalid(),
                            call.requestId(),
                        )
                    )
                }
                post("/{intentId}/authorise") {
                    val identity = call.identity()
                    limits.check("payments:write:${identity.userId}", 60, 60)
                    if (
                        call.request.contentType().withoutParameters() !=
                            ContentType.Application.Json
                    )
                        invalid()
                    val response =
                        payments.authorise(
                            identity,
                            call.parameters["merchantId"]?.canonicalUuid() ?: invalid(),
                            call.parameters["intentId"]?.canonicalUuid() ?: invalid(),
                            call.receive(),
                            call.paymentKey(),
                            call.requestId(),
                        )
                    call.respond(HttpStatusCode.fromValue(response.status), response.body)
                }
                post("/{intentId}/cancel") {
                    call.respond(
                        payments.cancel(
                            call.identity(),
                            call.parameters["merchantId"]?.canonicalUuid() ?: invalid(),
                            call.parameters["intentId"]?.canonicalUuid() ?: invalid(),
                            call.requestId(),
                        )
                    )
                }
            }
            get("/payments") {
                val limit =
                    call.request.queryParameters["limit"]?.toIntOrNull()
                        ?: if (call.request.queryParameters.contains("limit")) invalid() else 25
                call.respond(
                    payments.list(
                        call.identity(),
                        call.parameters["merchantId"]?.canonicalUuid() ?: invalid(),
                        limit,
                        call.request.queryParameters["cursor"],
                        call.request.queryParameters["from"],
                        call.request.queryParameters["to"],
                        call.request.queryParameters["status"],
                    )
                )
            }
            get("/payments/{paymentId}") {
                call.respond(
                    payments.detail(
                        call.identity(),
                        call.parameters["merchantId"]?.canonicalUuid() ?: invalid(),
                        call.parameters["paymentId"]?.canonicalUuid() ?: invalid(),
                        call.requestId(),
                    )
                )
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.paymentKey(): UUID =
    request.header("Idempotency-Key")?.canonicalUuid()?.takeIf {
        it.version() == 4 && it.variant() == 2
    } ?: invalid()
