package com.tapflow.api.payments

import com.tapflow.api.auth.Identity
import com.tapflow.api.auth.invalid
import com.tapflow.api.common.*
import com.tapflow.api.db.Database
import com.tapflow.api.merchants.MerchantAccess
import com.tapflow.protocol.SignatureVerifier
import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.*
import java.util.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class PaymentService(
    private val db: Database,
    clock: Clock = Clock.systemUTC(),
    private val nextId: () -> UUID = UUID::randomUUID,
    private val random: SecureRandom = SecureRandom(),
) {
    private val clock = Clock.tick(clock, Duration.ofMillis(1))
    private val access = MerchantAccess(db)
    private val repo = PaymentRepository()
    private val json = Json { encodeDefaults = true }

    private fun hash(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun uuid(raw: String): UUID =
        try {
            UUID.fromString(raw).also { require(it.toString() == raw) }
        } catch (_: IllegalArgumentException) {
            invalid()
        }

    private fun active(status: String) {
        if (status != "ACTIVE")
            throw DomainError(
                HttpStatusCode.Conflict,
                "TERMINAL_DISABLED",
                "This terminal is disabled.",
            )
    }

    suspend fun create(
        identity: Identity,
        merchant: UUID,
        request: CreateIntentRequest,
        key: UUID,
        requestId: UUID,
    ): Idempotency.Response {
        val terminal = uuid(request.terminalId)
        if (request.currency != "AUD")
            throw DomainError(
                HttpStatusCode.BadRequest,
                "UNSUPPORTED_CURRENCY",
                "Only AUD is supported.",
            )
        if (request.amountMinor !in 1..1_000_000 || (request.orderReference?.length ?: 0) > 120)
            invalid()
        val canonical =
            request.copy(
                terminalId = terminal.toString(),
                orderReference = request.orderReference?.trim()?.takeIf { it.isNotEmpty() },
            )
        return db.query { c ->
            access.require(c, identity.userId, merchant, lock = true)
            val status = repo.terminal(c, merchant, terminal, exclusive = true)
            Idempotency.execute(
                c,
                "payments:create:${identity.userId}:$merchant",
                key,
                hash(json.encodeToString(canonical)),
                clock.instant(),
                nextId,
            ) {
                active(status)
                val now = clock.instant()
                repo.expirePending(c, merchant, terminal, now)
                val intent =
                    repo.create(
                        c,
                        nextId(),
                        merchant,
                        terminal,
                        canonical,
                        ByteArray(16).also(random::nextBytes),
                        now,
                    )
                repo.seen(c, merchant, terminal, now)
                repo.audit(
                    c,
                    nextId(),
                    identity.userId,
                    intent,
                    "payment.intent_created",
                    requestId,
                    now,
                )
                Idempotency.Response(201, json.encodeToJsonElement(intent.wire()).jsonObject)
            }
        }
    }

    suspend fun authorise(
        identity: Identity,
        merchant: UUID,
        id: UUID,
        request: AuthoriseRequest,
        key: UUID,
        requestId: UUID,
    ): Idempotency.Response {
        val terminal = uuid(request.terminalId)
        val card = uuid(request.cardId)
        val signature =
            try {
                require(request.signatureDerBase64.length in 8..136)
                Base64.getDecoder().decode(request.signatureDerBase64).also {
                    require(SignatureVerifier.isDer(it))
                }
            } catch (_: IllegalArgumentException) {
                repo.badProof()
            }
        val canonical =
            AuthoriseRequest(
                terminal.toString(),
                card.toString(),
                Base64.getEncoder().encodeToString(signature),
            )
        val digest = hash(json.encodeToString(canonical))
        return db.query { c ->
            val merchantInfo = access.require(c, identity.userId, merchant, lock = true)
            val terminalStatus = repo.terminal(c, merchant, terminal)
            Idempotency.execute(
                c,
                "payments:authorise:${identity.userId}:$merchant:$id",
                key,
                digest,
                clock.instant(),
                nextId,
            ) {
                var intent = repo.intent(c, merchant, id)
                if (intent.terminal != terminal)
                    repo.conflict("The payment belongs to a different terminal.")
                if (intent.status in setOf("APPROVED", "DECLINED")) {
                    if (
                        intent.card != card ||
                            intent.proofHash == null ||
                            !MessageDigest.isEqual(intent.proofHash, digest)
                    )
                        repo.conflict()
                    return@execute Idempotency.Response(
                        200,
                        json.encodeToJsonElement(decision(intent, merchantInfo.name)).jsonObject,
                    )
                }
                if (
                    intent.status == "EXPIRED" ||
                        (intent.status == "AWAITING_TAP" && !intent.expiry.isAfter(clock.instant()))
                ) {
                    if (intent.status == "AWAITING_TAP") {
                        intent = repo.finish(c, intent, "EXPIRED", clock.instant())
                        repo.audit(
                            c,
                            nextId(),
                            identity.userId,
                            intent,
                            "payment.expired",
                            requestId,
                            clock.instant(),
                        )
                    }
                    return@execute expired(requestId)
                }
                if (intent.status != "AWAITING_TAP") repo.conflict()
                active(terminalStatus)
                val proofKey = repo.card(c, card)
                if (!SignatureVerifier.verify(intent.challenge(), card, proofKey.key, signature))
                    repo.badProof()
                val wallet = repo.wallet(c, card)
                val now = clock.instant()
                if (!intent.expiry.isAfter(now)) {
                    intent = repo.finish(c, intent, "EXPIRED", now)
                    repo.audit(
                        c,
                        nextId(),
                        identity.userId,
                        intent,
                        "payment.expired",
                        requestId,
                        now,
                    )
                    return@execute expired(requestId)
                }
                val code =
                    if (!proofKey.active) "CARD_BLOCKED"
                    else if (wallet.balance < intent.amount) "INSUFFICIENT_FUNDS" else "APPROVED"
                val status = if (code == "APPROVED") "APPROVED" else "DECLINED"
                if (status == "APPROVED") repo.debit(c, wallet, intent, nextId(), now)
                intent =
                    repo.finish(
                        c,
                        intent,
                        status,
                        now,
                        card,
                        digest,
                        code,
                        if (status == "APPROVED") "TF-${id.toString().uppercase(Locale.ROOT)}"
                        else null,
                    )
                repo.audit(
                    c,
                    nextId(),
                    identity.userId,
                    intent,
                    if (status == "APPROVED") "payment.approved" else "payment.declined",
                    requestId,
                    now,
                )
                Idempotency.Response(
                    200,
                    json.encodeToJsonElement(decision(intent, merchantInfo.name)).jsonObject,
                )
            }
        }
    }

    suspend fun get(identity: Identity, merchant: UUID, id: UUID, requestId: UUID): PaymentIntent =
        db.query { c ->
            access.require(c, identity.userId, merchant, lock = true)
            var intent = repo.intent(c, merchant, id)
            if (intent.status == "AWAITING_TAP" && !intent.expiry.isAfter(clock.instant())) {
                intent = repo.finish(c, intent, "EXPIRED", clock.instant())
                repo.audit(
                    c,
                    nextId(),
                    identity.userId,
                    intent,
                    "payment.expired",
                    requestId,
                    clock.instant(),
                )
            }
            intent.wire()
        }

    suspend fun cancel(
        identity: Identity,
        merchant: UUID,
        id: UUID,
        requestId: UUID,
    ): PaymentIntent = db.query { c ->
        access.require(c, identity.userId, merchant, lock = true)
        var intent = repo.intent(c, merchant, id)
        if (intent.status in setOf("APPROVED", "DECLINED"))
            repo.conflict("The payment was already decided. Check its current status.")
        if (intent.status == "AWAITING_TAP") {
            val now = clock.instant()
            val status = if (!intent.expiry.isAfter(now)) "EXPIRED" else "CANCELLED"
            intent = repo.finish(c, intent, status, now)
            repo.audit(
                c,
                nextId(),
                identity.userId,
                intent,
                if (status == "EXPIRED") "payment.expired" else "payment.cancelled",
                requestId,
                now,
            )
        }
        intent.wire()
    }

    suspend fun detail(
        identity: Identity,
        merchant: UUID,
        id: UUID,
        requestId: UUID,
    ): PaymentDetail {
        get(identity, merchant, id, requestId)
        return db.query { c ->
            c.transactionIsolation = java.sql.Connection.TRANSACTION_REPEATABLE_READ
            access.require(c, identity.userId, merchant)
            repo.details(c, merchant, id, clock.instant())
        }
    }

    suspend fun list(
        identity: Identity,
        merchant: UUID,
        limit: Int,
        cursor: String?,
        from: String?,
        to: String?,
        status: String?,
    ): PaymentPage {
        if (
            limit !in 1..100 ||
                (status != null &&
                    status !in
                        setOf("AWAITING_TAP", "APPROVED", "DECLINED", "EXPIRED", "CANCELLED"))
        )
            invalid()
        fun time(raw: String): Instant =
            try {
                Instant.parse(raw).also {
                    require(
                        raw.endsWith('Z') &&
                            it >= Instant.EPOCH &&
                            it <= Instant.parse("9999-12-31T23:59:59.999999Z") &&
                            it.nano % 1000 == 0
                    )
                }
            } catch (_: Exception) {
                invalid()
            }
        val end = to?.let(::time) ?: clock.instant().plusMillis(1)
        val start = from?.let(::time) ?: end.minus(Duration.ofDays(30))
        if (start >= end || Duration.between(start, end) > Duration.ofDays(366)) invalid()
        val after = cursor?.let { raw ->
            try {
                require(raw.length in 1..512)
                val parts = String(Base64.getUrlDecoder().decode(raw), Charsets.US_ASCII).split('|')
                require(parts.size == 3 && parts[0] == merchant.toString())
                val instant = time(parts[1])
                require(instant.toString() == parts[1])
                instant to uuid(parts[2])
            } catch (_: Exception) {
                invalid()
            }
        }
        return db.query { c ->
            access.require(c, identity.userId, merchant)
            val rows = repo.list(c, merchant, limit, after, start, end, status, clock.instant())
            val page = rows.take(limit)
            val next =
                if (rows.size > limit)
                    page.last().let {
                        Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                "$merchant|${it.createdAt}|${it.paymentId}"
                                    .toByteArray(Charsets.US_ASCII)
                            )
                    }
                else null
            PaymentPage(page, next)
        }
    }

    private fun decision(intent: StoredIntent, name: String) =
        AuthorisationResult(
            intent.id.toString(),
            intent.status,
            checkNotNull(intent.decision),
            intent.amount,
            intent.currency,
            merchantName = name,
            receiptNumber = intent.receipt,
            authorisedAt = checkNotNull(intent.authorisedAt).toString(),
        )

    private fun expired(requestId: UUID) =
        Idempotency.Response(
            410,
            json
                .encodeToJsonElement(
                    ErrorEnvelope(
                        ApiError(
                            "PAYMENT_INTENT_EXPIRED",
                            "This payment request expired. Check its status before starting another.",
                            requestId.toString(),
                        )
                    )
                )
                .jsonObject,
        )
}
