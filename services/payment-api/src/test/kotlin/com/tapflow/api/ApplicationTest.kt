package com.tapflow.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApplicationTest {
    @Test
    fun `liveness confirms the running demo process`() = testApplication {
        application { module() }
        val response = client.get("/health/live")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(JsonPrimitive(true), Json.parseToJsonElement(response.bodyAsText()).jsonObject["demo"])
    }

    @Test
    fun `status identifies bootstrap without claiming payment readiness`() = testApplication {
        application { module() }
        val response = client.get("/api/v1/status")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("bootstrap", Json.parseToJsonElement(response.bodyAsText()).jsonObject["phase"]?.jsonPrimitive?.content)
    }

    @Test
    fun `readiness stays unavailable until persistence is implemented`() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health/ready").status)
    }
}
