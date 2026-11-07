package com.tapflow.api.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.tapflow.api.config.AuthConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

class TokenService(config: AuthConfig, private val clock: Clock = Clock.systemUTC(), private val random: SecureRandom = SecureRandom(), private val nextId: () -> UUID = UUID::randomUUID) {
    private val algorithm = Algorithm.HMAC256(config.signingSecret)
    private val issuer = config.issuer
    private val audience = config.audience
    val verifier = (JWT.require(algorithm).withIssuer(issuer).withAudience(audience)
        .withClaimPresence("sub").withClaimPresence("exp").withClaimPresence("iat")
        .withClaimPresence("jti").withClaimPresence("sid").withClaimPresence("client") as JWTVerifier.BaseVerification).build(clock)

    fun access(userId: UUID, familyId: UUID, clientType: String): String = JWT.create()
        .withIssuer(issuer).withAudience(audience).withSubject(userId.toString())
        .withJWTId(nextId().toString()).withIssuedAt(clock.instant()).withExpiresAt(clock.instant().plusSeconds(900))
        .withClaim("sid", familyId.toString()).withClaim("client", clientType).sign(algorithm)

    fun opaque(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun hash(raw: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
}
