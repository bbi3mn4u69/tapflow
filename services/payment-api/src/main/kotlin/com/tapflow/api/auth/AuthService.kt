package com.tapflow.api.auth

import com.tapflow.api.common.DomainError
import com.tapflow.api.config.AuthConfig
import com.tapflow.api.db.Database
import de.mkammerer.argon2.Argon2Factory
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

class AuthService(private val db: Database, val config: AuthConfig, clock: Clock = Clock.systemUTC(), private val nextId: () -> UUID = UUID::randomUUID, random: SecureRandom = SecureRandom()) {
    // PostgreSQL stores microseconds; truncate before expiry math so persistence cannot round the deadline up.
    private val clock = Clock.tick(clock, Duration.ofNanos(1_000))
    val tokens = TokenService(config, this.clock, random, nextId)
    private val repo = AuthRepository(nextId)
    private val hashing = Dispatchers.Default.limitedParallelism(2)
    private val argon = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
    private val dummyHash = argon.hash(3, 65_536, 1, "not-an-account-password".toCharArray())

    suspend fun login(request: LoginRequest, client: String, requestId: UUID): MobileSession {
        val email = credentials(request)
        val candidate = db.query { repo.user(it, email) }
        val password = request.password.toCharArray()
        val verified = try { withContext(hashing) { argon.verify(candidate?.hash ?: dummyHash, password) } }
            finally { password.fill('\u0000') }
        if (!verified || candidate == null || !candidate.active) unauthorized()
        return db.query { c ->
            val user = repo.lockUser(c, candidate.id) ?: unauthorized()
            if (!user.active || user.hash != candidate.hash) unauthorized()
            val now = clock.instant()
            val family = nextId()
            val raw = tokens.opaque()
            repo.insertSession(c, nextId(), user.id, family, client, tokens.hash(raw), now, now.plusSeconds(config.refreshDays * 86_400L))
            repo.audit(c, user.id, "auth.login", requestId, now)
            MobileSession(tokens.access(user.id, family, client), raw, user = repo.profile(c, user))
        }
    }

    suspend fun refresh(raw: String, client: String, requestId: UUID): MobileSession {
        validateRefresh(raw)
        val result = db.query { c ->
            val hash = tokens.hash(raw)
            val initial = repo.session(c, hash, client) ?: return@query null
            // Lock the user before re-reading a session so refresh/replay/logout serialize across the family.
            val user = repo.lockUser(c, initial.userId) ?: return@query null
            val old = repo.session(c, hash, client) ?: return@query null
            val now = clock.instant()
            if (old.revoked) {
                if (old.rotated) {
                    repo.revoke(c, old.familyId, now)
                    repo.audit(c, user.id, "auth.refresh_reuse", requestId, now)
                }
                return@query null
            }
            if (!user.active || !old.expiresAt.isAfter(now)) return@query null
            val next = tokens.opaque()
            repo.revokeOne(c, old.id, now)
            repo.insertSession(c, nextId(), user.id, old.familyId, client, tokens.hash(next), now, old.expiresAt, old.id)
            MobileSession(tokens.access(user.id, old.familyId, client), next, user = repo.profile(c, user))
        }
        // Replay revocation must commit before the error is returned.
        return result ?: unauthorized()
    }

    suspend fun identity(userId: UUID, family: UUID, client: String): Identity? = db.query { c ->
        repo.activeIdentity(c, userId, family, client, clock.instant())?.let { Identity(userId, family, client) }
    }
    suspend fun me(identity: Identity): SessionUser = db.query { c ->
        val user = repo.activeIdentity(c, identity.userId, identity.familyId, identity.clientType, clock.instant()) ?: unauthorized()
        repo.profile(c, user)
    }
    suspend fun logout(raw: String?, client: String, requestId: UUID, identity: Identity? = null) {
        if (identity != null) {
            if (identity.clientType != client) unauthorized()
            if (raw == null || !raw.matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
            db.query { c ->
                repo.lockUser(c, identity.userId) ?: unauthorized()
                val presented = repo.session(c, tokens.hash(raw), client)
                if (presented != null && (presented.userId != identity.userId || presented.familyId != identity.familyId)) unauthorized()
                repo.revoke(c, identity.familyId, clock.instant())
                repo.audit(c, identity.userId, "auth.logout", requestId, clock.instant())
            }
            return
        }
        if (raw == null || !raw.matches(Regex("[A-Za-z0-9_-]{43}"))) return
        db.query { c ->
            val hash = tokens.hash(raw)
            val initial = repo.session(c, hash, client) ?: return@query
            repo.lockUser(c, initial.userId) ?: return@query
            repo.revoke(c, initial.familyId, clock.instant())
            repo.audit(c, initial.userId, "auth.logout", requestId, clock.instant())
        }
    }
    suspend fun register(request: LoginRequest, requestId: UUID): SessionUser {
        if (!config.registrationEnabled) throw DomainError(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "The resource was not found.")
        val email = credentials(request)
        if (request.password.length < 12) invalid()
        val password = request.password.toCharArray()
        val hash = try { withContext(hashing) { argon.hash(3, 65_536, 1, password) } } finally { password.fill('\u0000') }
        return db.query { c ->
            val user = repo.register(c, nextId(), email, hash)
                ?: throw DomainError(HttpStatusCode.Conflict, "ACCOUNT_ALREADY_EXISTS", "The account cannot be registered.")
            repo.audit(c, user.id, "auth.customer_registered", requestId, clock.instant())
            repo.profile(c, user)
        }
    }
    fun credentials(request: LoginRequest): String {
        val email = request.email.trim().lowercase(Locale.ROOT)
        if (email.length !in 3..254 || !email.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) || request.password.length !in 1..128) invalid()
        return email
    }
    private fun validateRefresh(raw: String) { if (!raw.matches(Regex("[A-Za-z0-9_-]{43}"))) unauthorized() }
}

fun unauthorized(): Nothing = throw DomainError(HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "Sign in to continue.")
fun invalid(): Nothing = throw DomainError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "The request is invalid.")
