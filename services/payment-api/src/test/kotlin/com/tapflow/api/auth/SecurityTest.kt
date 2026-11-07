package com.tapflow.api.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.tapflow.api.config.AuthConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class MutableClock(var now: Instant = Instant.now()) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}

class SecurityTest {
    private val config = AuthConfig("test-only-strong-signing-key-at-least-32-bytes", "issuer", "audience", setOf("https://portal.example.test"))

    @Test fun `access verification enforces expiry algorithm issuer audience and required claims`() {
        val clock = MutableClock()
        val service = TokenService(config, clock)
        val token = service.access(UUID.randomUUID(), UUID.randomUUID(), "MOBILE")
        assertEquals("HS256", service.verifier.verify(token).algorithm)
        val signature = token.substringAfterLast('.')
        val altered = token.substringBeforeLast('.') + "." + (if (signature[0] == 'A') "B" else "A") + signature.drop(1)
        assertThrows(JWTVerificationException::class.java) { service.verifier.verify(altered) }
        for ((algorithm, issuer, audience) in listOf(
            Triple(Algorithm.HMAC512(config.signingSecret), config.issuer, config.audience),
            Triple(Algorithm.HMAC256(config.signingSecret), "another-issuer", config.audience),
            Triple(Algorithm.HMAC256(config.signingSecret), config.issuer, "another-audience"),
            Triple(Algorithm.HMAC256("a-different-secret-with-at-least-32-bytes"), config.issuer, config.audience),
        )) {
            val wrong = JWT.create().withIssuer(issuer).withAudience(audience).withSubject(UUID.randomUUID().toString())
                .withJWTId(UUID.randomUUID().toString()).withIssuedAt(clock.instant()).withExpiresAt(clock.instant().plusSeconds(900))
                .withClaim("sid", UUID.randomUUID().toString()).withClaim("client", "MOBILE").sign(algorithm)
            assertThrows(JWTVerificationException::class.java) { service.verifier.verify(wrong) }
        }
        val missing = JWT.create().withIssuer(config.issuer).withAudience(config.audience).sign(Algorithm.HMAC256(config.signingSecret))
        assertThrows(JWTVerificationException::class.java) { service.verifier.verify(missing) }
        clock.now = clock.now.plusSeconds(900)
        assertThrows(JWTVerificationException::class.java) { service.verifier.verify(token) }
    }

    @Test fun `refresh secrets contain 256 random bits and only hashes are comparable persistence values`() {
        val tokens = TokenService(config)
        val first = tokens.opaque(); val second = tokens.opaque()
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertNotEquals(first, second)
        assertEquals(32, tokens.hash(first).size)
        assertFalse(tokens.hash(first).contentEquals(first.toByteArray()))
    }

    @Test fun `limits retain a fixed window then admit requests after expiry`() {
        val clock = MutableClock()
        val limits = RateLimits(clock)
        repeat(5) { limits.check("account:test", 5, 900) }
        val limited = assertThrows(Limited::class.java) { limits.check("account:test", 5, 900) }
        assertEquals(900L, limited.retryAfter)
        limits.check("account:other", 5, 900)
        clock.now = clock.now.plusSeconds(900)
        assertDoesNotThrow { limits.check("account:test", 5, 900) }
    }

    @Test fun `browser public URL must be an allowed exact origin even in development`() {
        val env = mapOf("APP_ENV" to "development", "APP_PUBLIC_URL" to "http://remote.example.test", "JWT_SIGNING_SECRET" to config.signingSecret,
            "JWT_ISSUER" to "issuer", "JWT_AUDIENCE" to "audience", "ALLOWED_ORIGINS" to "https://portal.example.test")
        assertThrows(IllegalArgumentException::class.java) { AuthConfig.fromEnvironment(env) }
        assertThrows(IllegalArgumentException::class.java) { AuthConfig.fromEnvironment(env + ("APP_PUBLIC_URL" to "https://another.example.test")) }
    }

    @Test fun `production config rejects placeholders weak keys wildcard and insecure origins`() {
        val env = mapOf("APP_ENV" to "production", "APP_PUBLIC_URL" to "https://portal.example.test", "JWT_SIGNING_SECRET" to config.signingSecret,
            "JWT_ISSUER" to "issuer", "JWT_AUDIENCE" to "audience", "ALLOWED_ORIGINS" to "https://portal.example.test")
        assertTrue(AuthConfig.fromEnvironment(env).secureCookie)
        for (bad in listOf(mapOf("JWT_SIGNING_SECRET" to "short"), mapOf("JWT_SIGNING_SECRET" to "REPLACE_WITH_32_PLUS_RANDOM_BYTES"),
            mapOf("APP_PUBLIC_URL" to "http://portal.example.test"), mapOf("ALLOWED_ORIGINS" to "*"),
            mapOf("ALLOWED_ORIGINS" to "http://portal.example.test"), mapOf("ACCESS_TOKEN_TTL_SECONDS" to "3600"))) {
            assertThrows(IllegalArgumentException::class.java) { AuthConfig.fromEnvironment(env + bad) }
        }
        assertFalse(AuthConfig.fromEnvironment(env + mapOf("APP_ENV" to "development", "APP_PUBLIC_URL" to "http://localhost:5173", "ALLOWED_ORIGINS" to "http://localhost:5173")).secureCookie)
    }
}
