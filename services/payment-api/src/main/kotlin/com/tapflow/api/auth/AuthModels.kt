package com.tapflow.api.auth

import io.ktor.server.auth.Principal
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class LoginRequest(val email: String, val password: String) {
    override fun toString() = "LoginRequest([redacted])"
}
@Serializable data class RefreshRequest(val refreshToken: String) {
    override fun toString() = "RefreshRequest([redacted])"
}
@Serializable data class Membership(val merchantId: String, val merchantName: String, val role: String)
@Serializable data class SessionUser(val id: String, val email: String, val accountType: String, val memberships: List<Membership>)
@Serializable data class WebSession(val accessToken: String, val expiresInSeconds: Int = 900, val user: SessionUser, val demo: Boolean = true) {
    override fun toString() = "WebSession([redacted])"
}
@Serializable data class MobileSession(val accessToken: String, val refreshToken: String, val expiresInSeconds: Int = 900, val user: SessionUser, val demo: Boolean = true) {
    override fun toString() = "MobileSession([redacted])"
    fun web() = WebSession(accessToken, expiresInSeconds, user)
}
data class Identity(val userId: UUID, val familyId: UUID, val clientType: String) : Principal
