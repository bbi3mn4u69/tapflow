package com.tapflow.api.config

import java.net.URI

class AuthConfig(
    val signingSecret: String,
    val issuer: String,
    val audience: String,
    val allowedOrigins: Set<String>,
    val secureCookie: Boolean = true,
    val refreshDays: Int = 14,
    val registrationEnabled: Boolean = false,
) {
    init {
        require(signingSecret.toByteArray().size >= 32 && !signingSecret.startsWith("REPLACE_")) { "Supply a strong JWT_SIGNING_SECRET" }
        require(issuer.isNotBlank() && audience.isNotBlank()) { "JWT issuer and audience are required" }
        require(refreshDays in 1..30) { "Invalid REFRESH_TOKEN_TTL_DAYS" }
        require(allowedOrigins.isNotEmpty() && allowedOrigins.all { raw ->
            runCatching {
                val u = URI(raw)
                u.scheme in setOf("http", "https") && u.host != null && u.userInfo == null &&
                    u.rawPath.isNullOrEmpty() && u.rawQuery == null && u.rawFragment == null &&
                    (u.scheme == "https" || (!secureCookie && u.host in setOf("localhost", "127.0.0.1", "[::1]")))
            }.getOrDefault(false)
        }) { "ALLOWED_ORIGINS must contain exact HTTPS origins (loopback HTTP in development only)" }
    }
    override fun toString() = "AuthConfig([redacted])"

    companion object {
        fun fromEnvironment(env: Map<String, String>): AuthConfig {
            fun required(key: String) = env[key]?.takeIf { it.isNotBlank() && !it.startsWith("REPLACE_") }
                ?: throw IllegalArgumentException("Missing required configuration: $key")
            val development = (env["APP_ENV"] ?: "development") in setOf("development", "test")
            val publicOrigin = required("APP_PUBLIC_URL")
            val publicUrl = URI(publicOrigin)
            require(development || publicUrl.scheme == "https") { "APP_PUBLIC_URL must use HTTPS" }
            val origins = required("ALLOWED_ORIGINS").split(',').map { it.trim() }.toSet()
            require(publicOrigin in origins) { "APP_PUBLIC_URL must be an allowed exact origin" }
            require((env["ACCESS_TOKEN_TTL_SECONDS"] ?: "900") == "900") { "Access token TTL is fixed at 900 seconds" }
            val registration = env["DEMO_CUSTOMER_REGISTRATION_ENABLED"] ?: "false"
            require(registration in setOf("true", "false")) { "Invalid registration flag" }
            require(development || registration == "false") { "Public registration is disabled outside development/test" }
            return AuthConfig(required("JWT_SIGNING_SECRET"), required("JWT_ISSUER"), required("JWT_AUDIENCE"),
                origins,
                secureCookie = !(development && publicUrl.scheme == "http"),
                refreshDays = (env["REFRESH_TOKEN_TTL_DAYS"] ?: "14").toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid REFRESH_TOKEN_TTL_DAYS"),
                registrationEnabled = registration == "true")
        }
    }
}
