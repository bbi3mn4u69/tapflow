package com.tapflow.api.config

import com.tapflow.api.common.ApiError
import com.tapflow.api.common.DomainError
import com.tapflow.api.common.ErrorEnvelope
import com.tapflow.api.auth.Limited
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.util.UUID

fun Application.configurePlugins(nextRequestId: () -> UUID) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
    install(CallId) {
        retrieve { call ->
            call.request.header("X-Request-ID")?.takeIf { it.length == 36 }?.let { raw ->
                runCatching { UUID.fromString(raw) }.getOrNull()?.toString()?.takeIf { it.equals(raw, ignoreCase = true) }
            }
        }
        generate { nextRequestId().toString() }
        replyToHeader("X-Request-ID")
    }
    install(CallLogging) {
        callIdMdc("requestId")
        format { call -> "http.request method=${call.request.httpMethod.value} status=${call.response.status()?.value ?: 0} durationMs=${call.processingTimeMillis()}" }
    }
    install(RequestBodyLimit) { bodyLimit { 16_384L } }
    install(StatusPages) {
        exception<Limited> { call, error ->
            call.response.headers.append("Retry-After", error.retryAfter.toString())
            call.respond(HttpStatusCode.TooManyRequests, ErrorEnvelope(ApiError("RATE_LIMITED", "Too many requests. Try again later.", checkNotNull(call.callId))))
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorEnvelope(ApiError("VALIDATION_ERROR", "The request is too large.", checkNotNull(call.callId))))
        }
        exception<DomainError> { call, error ->
            call.respond(error.status, ErrorEnvelope(ApiError(error.code, error.message, checkNotNull(call.callId))))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ApiError("VALIDATION_ERROR", "The request is invalid.", checkNotNull(call.callId))))
        }
        exception<Exception> { call, failure ->
            if (failure is CancellationException) throw failure
            this@configurePlugins.log.error("Request failed; requestId={} exceptionType={}", call.callId, failure.javaClass.simpleName)
            call.respond(HttpStatusCode.InternalServerError, ErrorEnvelope(ApiError("INTERNAL_ERROR", "An unexpected error occurred.", checkNotNull(call.callId))))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorEnvelope(ApiError("RESOURCE_NOT_FOUND", "The resource was not found.", checkNotNull(call.callId))))
        }
    }
}
