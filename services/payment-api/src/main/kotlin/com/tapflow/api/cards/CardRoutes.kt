package com.tapflow.api.cards

import com.tapflow.api.auth.*
import io.ktor.http.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.cardRoutes(cards: CardService, limits: RateLimits) {
    authenticate("access") {
        route("/api/v1/cards") {
            post {
                val identity = call.identity()
                limits.check("cards:enroll:${identity.userId}", 60, 60)
                val key =
                    call.request.header("Idempotency-Key")?.canonicalUuid()?.takeIf {
                        it.version() == 4 && it.variant() == 2
                    } ?: invalid()
                if (call.request.contentType().withoutParameters() != ContentType.Application.Json)
                    invalid()
                val response =
                    cards.enroll(identity, call.receive<EnrollmentRequest>(), key, call.requestId())
                call.respond(HttpStatusCode.fromValue(response.status), response.body)
            }
            get { call.respond(cards.list(call.identity())) }
            route("/{cardId}") {
                get {
                    call.respond(
                        cards.get(
                            call.identity(),
                            call.parameters["cardId"]?.canonicalUuid() ?: invalid(),
                        )
                    )
                }
                get("/wallet") {
                    call.respond(
                        cards.wallet(
                            call.identity(),
                            call.parameters["cardId"]?.canonicalUuid() ?: invalid(),
                        )
                    )
                }
                post("/disable") {
                    call.respond(
                        cards.disable(
                            call.identity(),
                            call.parameters["cardId"]?.canonicalUuid() ?: invalid(),
                            call.requestId(),
                        )
                    )
                }
                get("/activity") {
                    val limit =
                        call.request.queryParameters["limit"]?.toIntOrNull()
                            ?: if (call.request.queryParameters.contains("limit")) invalid() else 25
                    call.respond(
                        cards.activity(
                            call.identity(),
                            call.parameters["cardId"]?.canonicalUuid() ?: invalid(),
                            limit,
                            call.request.queryParameters["cursor"],
                        )
                    )
                }
            }
        }
    }
}
