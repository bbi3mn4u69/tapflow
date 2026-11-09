package com.tapflow.api.merchants

import com.tapflow.api.auth.*
import com.tapflow.api.payments.RefundService
import io.ktor.http.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.operationsRoutes(refunds: RefundService, reports: ReportsService, limits: RateLimits) {
    authenticate("access") {
        route("/api/v1/merchants/{merchantId}") {
            post("/payments/{paymentId}/refunds") {
                val identity = call.identity()
                limits.check("refunds:${identity.userId}", 60, 60)
                if (call.request.contentType().withoutParameters() != ContentType.Application.Json)
                    invalid()
                val key =
                    call.request.header("Idempotency-Key")?.canonicalUuid()?.takeIf {
                        it.version() == 4 && it.variant() == 2
                    } ?: invalid()
                val result =
                    refunds.create(
                        identity,
                        call.parameters["merchantId"]?.canonicalUuid() ?: invalid(),
                        call.parameters["paymentId"]?.canonicalUuid() ?: invalid(),
                        call.receive(),
                        key,
                        call.requestId(),
                    )
                call.respond(HttpStatusCode.fromValue(result.status), result.body)
            }
            get("/dashboard") {
                call.respond(
                    reports.dashboard(
                        call.identity(),
                        call.parameters["merchantId"]?.canonicalUuid() ?: invalid(),
                        call.request.queryParameters["from"],
                        call.request.queryParameters["to"],
                    )
                )
            }
            get("/audit-events") {
                val limit =
                    call.request.queryParameters["limit"]?.toIntOrNull()
                        ?: if (call.request.queryParameters.contains("limit")) invalid() else 25
                call.respond(
                    reports.audit(
                        call.identity(),
                        call.parameters["merchantId"]?.canonicalUuid() ?: invalid(),
                        limit,
                        call.request.queryParameters["cursor"],
                        call.request.queryParameters["from"],
                        call.request.queryParameters["to"],
                        call.request.queryParameters["eventType"],
                    )
                )
            }
        }
    }
}
