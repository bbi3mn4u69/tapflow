package com.tapflow.api

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

object HealthProbe {
    fun healthy(uri: URI): Boolean = runCatching {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().use { client ->
            client
                .send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
                .statusCode() == 200
        }
    }
        .getOrDefault(false)

    @JvmStatic
    fun main(args: Array<String>) {
        val port = System.getenv("API_PORT") ?: System.getenv("PORT") ?: "8080"
        exitProcess(
            if (
                runCatching { healthy(URI("http://127.0.0.1:$port/health/ready")) }
                    .getOrDefault(false)
            )
                0
            else 1
        )
    }
}
