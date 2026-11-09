package com.tapflow.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ApplicationTest {
    @Test
    fun `liveness confirms the running demo process`() = testApplication {
        application { module() }
        val response = client.get("/health/live")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            JsonPrimitive(true),
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["demo"],
        )
    }

    @Test
    fun `status identifies the merchant operations phase`() = testApplication {
        application { module() }
        val response = client.get("/api/v1/status")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "merchant-operations",
            Json.parseToJsonElement(response.bodyAsText())
                .jsonObject["phase"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `readiness stays unavailable until persistence is implemented`() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health/ready").status)
    }

    @Test
    fun `returns a validated request ID and stable not found envelope`() = testApplication {
        application { module() }
        val requestId = UUID.randomUUID().toString()
        val response = client.get("/missing") { header("X-Request-ID", requestId) }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(requestId, response.headers["X-Request-ID"])
        val error =
            Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
        assertEquals("RESOURCE_NOT_FOUND", error.getValue("code").jsonPrimitive.content)
        assertEquals(requestId, error.getValue("requestId").jsonPrimitive.content)
    }

    @Test
    fun `replaces invalid request IDs and makes unready responses traceable`() = testApplication {
        application { module() }
        val response = client.get("/health/ready") { header("X-Request-ID", "not-a-valid-id") }
        val requestId = response.headers["X-Request-ID"]
        assertNotNull(requestId)
        UUID.fromString(requestId)
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val error =
            Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
        assertEquals(requestId, error.getValue("requestId").jsonPrimitive.content)
    }

    @Test
    fun `serves the packaged API contract without credentials`() = testApplication {
        application { module() }
        val response = client.get("/api/v1/openapi.yaml")
        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(response.bodyAsText().contains("DATABASE_PASSWORD="))
        org.junit.jupiter.api.Assertions.assertTrue(
            response.bodyAsText().contains("openapi: 3.1.0")
        )
    }

    @Test
    fun `unexpected failures never expose exception details`() = testApplication {
        application {
            module()
            routing { get("/_test/failure") { error("sensitive-internal-detail") } }
        }
        val response = client.get("/_test/failure")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("sensitive-internal-detail"))
        assertEquals(
            "INTERNAL_ERROR",
            Json.parseToJsonElement(body)
                .jsonObject
                .getValue("error")
                .jsonObject
                .getValue("code")
                .jsonPrimitive
                .content,
        )
    }
}
