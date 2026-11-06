package com.tapflow.api.common

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class DomainError(val status: HttpStatusCode, val code: String, override val message: String) : RuntimeException(message)

@Serializable
data class ErrorEnvelope(val error: ApiError, val demo: Boolean = true)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val requestId: String,
    val details: JsonObject = JsonObject(emptyMap()),
)
