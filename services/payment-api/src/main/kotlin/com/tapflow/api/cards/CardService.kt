package com.tapflow.api.cards

import com.tapflow.api.auth.Identity
import com.tapflow.api.auth.Limited
import com.tapflow.api.auth.invalid
import com.tapflow.api.common.DomainError
import com.tapflow.api.common.Idempotency
import com.tapflow.api.config.CardConfig
import com.tapflow.api.db.Database
import com.tapflow.protocol.SignatureVerifier
import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class CardService(
    private val db: Database,
    private val config: CardConfig,
    clock: Clock = Clock.systemUTC(),
    private val nextId: () -> UUID = UUID::randomUUID,
) {
    private val clock = Clock.tick(clock, Duration.ofNanos(1000))
    private val repo = CardRepository()
    private val json = Json { encodeDefaults = true }

    suspend fun enroll(
        identity: Identity,
        request: EnrollmentRequest,
        key: UUID,
        requestId: UUID,
    ): Idempotency.Response {
        if (!config.enrollmentEnabled)
            throw DomainError(
                HttpStatusCode.Forbidden,
                "ENROLLMENT_DISABLED",
                "Demo card enrollment is disabled.",
            )
        val label = request.label.trim()
        if (
            label.length !in 1..80 ||
                request.algorithm != "P256_SHA256_ECDSA" ||
                request.publicKeySpkiBase64.length !in 64..1368
        )
            invalid()
        val spki =
            try {
                Base64.getDecoder().decode(request.publicKeySpkiBase64).also {
                    SignatureVerifier.publicKey(it)
                }
            } catch (_: Exception) {
                invalid()
            }
        val canonical =
            EnrollmentRequest(label, request.algorithm, Base64.getEncoder().encodeToString(spki))
        val hash =
            MessageDigest.getInstance("SHA-256")
                .digest(json.encodeToString(canonical).toByteArray(Charsets.UTF_8))
        return db.query { c ->
            repo.customer(c, identity.userId, lock = true)
            val now = clock.instant()
            Idempotency.execute(c, "cards:enroll:${identity.userId}", key, hash, now, nextId) {
                var card = repo.existing(c, identity.userId, spki, label)
                if (card == null) {
                    val counts = repo.counts(c, identity.userId, now.minusSeconds(3600))
                    if (counts.first >= 100)
                        throw DomainError(
                            HttpStatusCode.Conflict,
                            "CARD_LIMIT_REACHED",
                            "The demo account card limit was reached.",
                        )
                    if (counts.second >= 3) throw Limited(3600)
                    card = repo.insert(c, identity.userId, nextId(), spki, label, now)
                    if (card != null) {
                        repo.fund(
                            c,
                            UUID.fromString(card.cardId),
                            nextId(),
                            nextId(),
                            config.initialBalanceMinor,
                            now,
                        )
                        repo.audit(
                            c,
                            nextId(),
                            identity.userId,
                            UUID.fromString(card.cardId),
                            "card.enrolled",
                            requestId,
                            now,
                        )
                    } else card = checkNotNull(repo.existing(c, identity.userId, spki, label))
                }
                Idempotency.Response(201, json.encodeToJsonElement(checkNotNull(card)).jsonObject)
            }
        }
    }

    suspend fun list(identity: Identity): CardList = db.query { c ->
        repo.customer(c, identity.userId)
        CardList(repo.list(c, identity.userId))
    }

    suspend fun get(identity: Identity, id: UUID): Card = db.query { c ->
        repo.customer(c, identity.userId)
        repo.card(c, identity.userId, id)
    }

    suspend fun wallet(identity: Identity, id: UUID): Wallet = db.query { c ->
        repo.customer(c, identity.userId)
        repo.wallet(c, identity.userId, id)
    }

    suspend fun disable(identity: Identity, id: UUID, requestId: UUID): Card = db.query { c ->
        repo.customer(c, identity.userId, lock = true)
        val old = repo.card(c, identity.userId, id, lock = true)
        if (old.status == "BLOCKED") old
        else
            repo.disable(c, identity.userId, id).also {
                repo.audit(
                    c,
                    nextId(),
                    identity.userId,
                    id,
                    "card.disabled",
                    requestId,
                    clock.instant(),
                )
            }
    }

    suspend fun activity(identity: Identity, id: UUID, limit: Int, cursor: String?): ActivityPage {
        if (limit !in 1..100) invalid()
        val after = cursor?.let { raw ->
            if (raw.length !in 1..512) invalid()
            try {
                val parts = String(Base64.getUrlDecoder().decode(raw), Charsets.US_ASCII).split('|')
                require(parts.size == 3 && parts[0] == id.toString())
                val time = Instant.parse(parts[1])
                require(
                    time >= Instant.EPOCH &&
                        time <= Instant.parse("9999-12-31T23:59:59.999999Z") &&
                        time.nano % 1000 == 0 &&
                        time.toString() == parts[1]
                )
                val entry = UUID.fromString(parts[2])
                require(entry.toString() == parts[2])
                time to entry
            } catch (_: Exception) {
                invalid()
            }
        }
        return db.query { c ->
            repo.customer(c, identity.userId)
            repo.card(c, identity.userId, id)
            val rows = repo.activity(c, identity.userId, id, limit, after)
            val page = rows.take(limit)
            val next =
                if (rows.size > limit)
                    page.last().let {
                        Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                "$id|${it.createdAt}|${it.id}".toByteArray(Charsets.US_ASCII)
                            )
                    }
                else null
            ActivityPage(page, next)
        }
    }
}
