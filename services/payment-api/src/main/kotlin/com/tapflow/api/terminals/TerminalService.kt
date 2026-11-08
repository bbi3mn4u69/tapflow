package com.tapflow.api.terminals

import com.tapflow.api.auth.Identity
import com.tapflow.api.auth.invalid
import com.tapflow.api.common.DomainError
import com.tapflow.api.common.Idempotency
import com.tapflow.api.db.Database
import com.tapflow.api.merchants.MerchantAccess
import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class TerminalService(
    private val db: Database,
    secret: String,
    clock: Clock = Clock.systemUTC(),
    private val nextId: () -> UUID = UUID::randomUUID,
) {
    private val secret = secret.toByteArray(Charsets.UTF_8)
    private val clock = Clock.tick(clock, Duration.ofNanos(1000))
    private val access = MerchantAccess(db)
    private val json = Json { encodeDefaults = true }

    private fun hash(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun code(user: UUID, merchant: UUID, key: UUID): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        val bytes =
            mac.doFinal("TAPFLOW-PAIRING-V1|$user|$merchant|$key".toByteArray(Charsets.US_ASCII))
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        return (0 until 13).map { alphabet[bytes[it].toInt() and 31] }.joinToString("")
    }

    suspend fun create(
        identity: Identity,
        merchant: UUID,
        request: PairingCodeRequest,
        key: UUID,
        requestId: UUID,
    ): Idempotency.Response {
        val hint = request.labelHint?.trim()
        if (hint != null && hint.length > 80) invalid()
        val normalized = PairingCodeRequest(hint)
        val raw = code(identity.userId, merchant, key)
        val digest = hash(raw)
        return db.query { c ->
            access.require(c, identity.userId, merchant, owner = true)
            val now = clock.instant()
            val response =
                Idempotency.execute(
                    c,
                    "terminals:code:${identity.userId}:$merchant",
                    key,
                    hash(json.encodeToString(normalized)),
                    now,
                    nextId,
                ) {
                    c.prepareStatement(
                            "insert into terminal_pairing_codes(id,merchant_id,code_hash,expires_at,created_by,created_at) values (?,?,?,?,?,?)"
                        )
                        .use {
                            it.setObject(1, nextId())
                            it.setObject(2, merchant)
                            it.setBytes(3, digest)
                            it.setTimestamp(4, Timestamp.from(now.plusSeconds(600)))
                            it.setObject(5, identity.userId)
                            it.setTimestamp(6, Timestamp.from(now))
                            it.executeUpdate()
                        }
                    audit(
                        c,
                        identity.userId,
                        merchant,
                        "terminal.pairing_code_created",
                        requestId,
                        now,
                    )
                    Idempotency.Response(
                        201,
                        buildJsonObject {
                            put("merchantId", merchant.toString())
                            put("expiresAt", now.plusSeconds(600).toString())
                            put("demo", true)
                            put("codeHash", Base64.getEncoder().encodeToString(digest))
                        },
                    )
                }
            // Reconstruct only in memory; replay records never contain the human-enterable secret.
            if (
                !MessageDigest.isEqual(
                    Base64.getDecoder()
                        .decode(response.body.getValue("codeHash").jsonPrimitive.content),
                    digest,
                )
            )
                throw DomainError(
                    HttpStatusCode.Conflict,
                    "PAIRING_CODE_UNAVAILABLE",
                    "Create a new pairing code after server key rotation.",
                )
            Idempotency.Response(
                response.status,
                JsonObject(
                    response.body.filterKeys { it != "codeHash" } +
                        ("pairingCode" to JsonPrimitive(raw))
                ),
            )
        }
    }

    suspend fun pair(
        identity: Identity,
        request: PairTerminalRequest,
        key: UUID,
        requestId: UUID,
    ): Idempotency.Response {
        val raw = request.pairingCode.trim().uppercase(java.util.Locale.ROOT)
        if (!raw.matches(Regex("[0-9A-HJKMNP-TV-Z]{13}"))) invalid()
        val installation =
            try {
                UUID.fromString(request.installationId).also {
                    require(
                        it.toString() == request.installationId &&
                            it.version() == 4 &&
                            it.variant() == 2
                    )
                }
            } catch (_: IllegalArgumentException) {
                invalid()
            }
        val label = request.label.trim()
        if (label.length !in 1..80) invalid()
        val digest = hash(raw)
        val canonical = PairTerminalRequest(raw, installation.toString(), label)
        return db.query { c ->
            val merchant =
                c.prepareStatement(
                        "select merchant_id from terminal_pairing_codes where code_hash=?"
                    )
                    .use {
                        it.setBytes(1, digest)
                        it.executeQuery().use { r ->
                            if (!r.next()) rejected()
                            r.getObject(1, UUID::class.java)
                        }
                    }
            access.require(c, identity.userId, merchant)
            val now = clock.instant()
            Idempotency.execute(
                c,
                "terminals:pair:${identity.userId}:$merchant",
                key,
                hash(json.encodeToString(canonical)),
                now,
                nextId,
            ) {
                val expires =
                    c.prepareStatement(
                            "select consumed_at,expires_at from terminal_pairing_codes where code_hash=? for update"
                        )
                        .use {
                            it.setBytes(1, digest)
                            it.executeQuery().use { r ->
                                check(r.next())
                                if (r.getTimestamp(1) != null) rejected(HttpStatusCode.Conflict)
                                r.getTimestamp(2).toInstant()
                            }
                        }
                c.prepareStatement("select id from merchants where id=? for update").use {
                    it.setObject(1, merchant)
                    it.executeQuery().use { r -> check(r.next()) }
                }
                val now = clock.instant()
                if (!expires.isAfter(now))
                    throw DomainError(
                        HttpStatusCode.Gone,
                        "PAIRING_CODE_EXPIRED",
                        "The pairing code expired.",
                    )
                val existing =
                    c.prepareStatement("select * from terminals where installation_id=? for update")
                        .use {
                            it.setObject(1, installation)
                            it.executeQuery().use { r -> if (r.next()) r.terminal() else null }
                        }
                val terminal =
                    if (existing != null) {
                        if (existing.merchantId != merchant.toString() || existing.label != label)
                            throw DomainError(
                                HttpStatusCode.Conflict,
                                "INSTALLATION_ALREADY_PAIRED",
                                "This installation is already paired.",
                            )
                        if (existing.status != "ACTIVE")
                            throw DomainError(
                                HttpStatusCode.Conflict,
                                "TERMINAL_DISABLED",
                                "This terminal is disabled.",
                            )
                        existing
                    } else {
                        c.prepareStatement("select count(*) from terminals where merchant_id=?")
                            .use {
                                it.setObject(1, merchant)
                                it.executeQuery().use { r ->
                                    r.next()
                                    if (r.getInt(1) >= 100)
                                        throw DomainError(
                                            HttpStatusCode.Conflict,
                                            "TERMINAL_LIMIT_REACHED",
                                            "The merchant terminal limit was reached.",
                                        )
                                }
                            }
                        c.prepareStatement(
                                "insert into terminals(id,merchant_id,label,installation_id,paired_by_user_id,paired_at) values (?,?,?,?,?,?) on conflict(installation_id) do nothing returning *"
                            )
                            .use {
                                it.setObject(1, nextId())
                                it.setObject(2, merchant)
                                it.setString(3, label)
                                it.setObject(4, installation)
                                it.setObject(5, identity.userId)
                                it.setTimestamp(6, Timestamp.from(now))
                                it.executeQuery().use { r ->
                                    if (!r.next())
                                        throw DomainError(
                                            HttpStatusCode.Conflict,
                                            "INSTALLATION_ALREADY_PAIRED",
                                            "This installation is already paired.",
                                        )
                                    r.terminal()
                                }
                            }
                    }
                val consumedAt = clock.instant()
                if (!expires.isAfter(consumedAt))
                    throw DomainError(
                        HttpStatusCode.Gone,
                        "PAIRING_CODE_EXPIRED",
                        "The pairing code expired.",
                    )
                c.prepareStatement(
                        "update terminal_pairing_codes set consumed_at=? where code_hash=?"
                    )
                    .use {
                        it.setTimestamp(1, Timestamp.from(consumedAt))
                        it.setBytes(2, digest)
                        it.executeUpdate()
                    }
                audit(
                    c,
                    identity.userId,
                    UUID.fromString(terminal.terminalId),
                    "terminal.paired",
                    requestId,
                    consumedAt,
                )
                Idempotency.Response(201, json.encodeToJsonElement(terminal).jsonObject)
            }
        }
    }

    suspend fun list(
        identity: Identity,
        merchant: UUID,
        limit: Int,
        cursor: String?,
    ): TerminalPage {
        if (limit !in 1..100) invalid()
        val after = cursor?.let { raw ->
            try {
                require(raw.length in 1..512)
                val parts = String(Base64.getUrlDecoder().decode(raw), Charsets.US_ASCII).split('|')
                require(parts.size == 3 && parts[0] == merchant.toString())
                val time = Instant.parse(parts[1])
                require(
                    time >= Instant.EPOCH &&
                        time <= Instant.parse("9999-12-31T23:59:59.999999Z") &&
                        time.nano % 1000 == 0 &&
                        time.toString() == parts[1]
                )
                val id = UUID.fromString(parts[2])
                require(id.toString() == parts[2])
                time to id
            } catch (_: Exception) {
                invalid()
            }
        }
        return db.query { c ->
            access.require(c, identity.userId, merchant)
            val filter = if (after != null) " and (paired_at,id)<(?,?)" else ""
            val rows =
                c.prepareStatement(
                        "select * from terminals where merchant_id=?$filter order by paired_at desc,id desc limit ?"
                    )
                    .use {
                        it.setObject(1, merchant)
                        var index = 2
                        if (after != null) {
                            it.setTimestamp(index++, Timestamp.from(after.first))
                            it.setObject(index++, after.second)
                        }
                        it.setInt(index, limit + 1)
                        it.executeQuery().use { r ->
                            buildList { while (r.next()) add(r.terminal()) }
                        }
                    }
            val page = rows.take(limit)
            val next =
                if (rows.size > limit)
                    page.last().let {
                        Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                "$merchant|${it.pairedAt}|${it.terminalId}"
                                    .toByteArray(Charsets.US_ASCII)
                            )
                    }
                else null
            TerminalPage(page, next)
        }
    }

    suspend fun disable(
        identity: Identity,
        merchant: UUID,
        terminal: UUID,
        requestId: UUID,
    ): Terminal = db.query { c ->
        access.require(c, identity.userId, merchant, owner = true)
        val old =
            c.prepareStatement("select * from terminals where merchant_id=? and id=? for update")
                .use {
                    it.setObject(1, merchant)
                    it.setObject(2, terminal)
                    it.executeQuery().use { r ->
                        if (!r.next())
                            throw DomainError(
                                HttpStatusCode.NotFound,
                                "RESOURCE_NOT_FOUND",
                                "The resource was not found.",
                            )
                        r.terminal()
                    }
                }
        if (old.status == "DISABLED") old
        else {
            val updated =
                c.prepareStatement(
                        "update terminals set status='DISABLED' where merchant_id=? and id=? returning *"
                    )
                    .use {
                        it.setObject(1, merchant)
                        it.setObject(2, terminal)
                        it.executeQuery().use { r ->
                            check(r.next())
                            r.terminal()
                        }
                    }
            audit(c, identity.userId, terminal, "terminal.disabled", requestId, clock.instant())
            updated
        }
    }

    private fun audit(
        c: Connection,
        user: UUID,
        resource: UUID,
        event: String,
        requestId: UUID,
        now: Instant,
    ) {
        c.prepareStatement(
                "insert into audit_events(id,actor_user_id,event_type,resource_type,resource_id,request_id,created_at) values (?,?,?,?,?,?,?)"
            )
            .use {
                it.setObject(1, nextId())
                it.setObject(2, user)
                it.setString(3, event)
                it.setString(
                    4,
                    if (event == "terminal.pairing_code_created") "merchant" else "terminal",
                )
                it.setObject(5, resource)
                it.setObject(6, requestId)
                it.setTimestamp(7, Timestamp.from(now))
                it.executeUpdate()
            }
    }

    private fun ResultSet.terminal() =
        Terminal(
            getString("id"),
            getString("merchant_id"),
            getString("label"),
            getString("status"),
            getTimestamp("paired_at").toInstant().toString(),
            getTimestamp("last_seen_at")?.toInstant()?.toString(),
        )

    private fun rejected(status: HttpStatusCode = HttpStatusCode.BadRequest): Nothing =
        throw DomainError(
            status,
            "PAIRING_CODE_INVALID",
            "The pairing code is invalid or already used.",
        )
}
