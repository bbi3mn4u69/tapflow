package com.tapflow.api.config

import java.net.URI

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val poolSize: Int = 5,
) {
    override fun toString() = "DatabaseConfig([redacted])"
}

data class AppConfig(val host: String, val port: Int, val environment: String, val database: DatabaseConfig) {
    companion object {
        fun fromEnvironment(env: Map<String, String>): AppConfig {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() && !it.startsWith("REPLACE_") }
                ?: throw IllegalArgumentException("Missing required configuration: $name")
            fun bounded(name: String, default: Int, range: IntRange): Int {
                val value = env[name]?.toIntOrNull() ?: if (env.containsKey(name)) null else default
                require(value != null && value in range) { "Invalid configuration: $name" }
                return value
            }
            val url = required("DATABASE_URL")
            require(url.startsWith("jdbc:postgresql://")) { "DATABASE_URL must be a PostgreSQL JDBC URL" }
            val uri = runCatching { URI(url.removePrefix("jdbc:")) }.getOrNull()
            require(uri != null && uri.host != null && !uri.path.isNullOrBlank() && uri.path != "/") {
                "Invalid DATABASE_URL"
            }
            val parameters = uri.query.orEmpty().split('&').filter { it.isNotEmpty() }
                .map { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }
            require(uri.userInfo == null && parameters.none { it.first in setOf("user", "password") }) {
                "Keep database credentials in DATABASE_USER and DATABASE_PASSWORD"
            }
            val loopback = uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
            val sslModes = parameters.filter { it.first == "sslmode" }.map { it.second }
            require(loopback || (sslModes.size == 1 && sslModes.single() in setOf("require", "verify-ca", "verify-full"))) {
                "Remote DATABASE_URL must require TLS"
            }
            val environment = env["APP_ENV"] ?: "development"
            require(environment in setOf("development", "test", "staging", "production")) { "Invalid APP_ENV" }
            require(env["DEMO_MODE"] == "true") { "TapFlow requires DEMO_MODE=true" }
            return AppConfig(
                host = env["API_HOST"]?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
                port = bounded("API_PORT", 8080, 1..65535),
                environment = environment,
                database = DatabaseConfig(url, required("DATABASE_USER"), required("DATABASE_PASSWORD"),
                    bounded("DATABASE_POOL_SIZE", 5, 1..20)),
            )
        }
    }
}
