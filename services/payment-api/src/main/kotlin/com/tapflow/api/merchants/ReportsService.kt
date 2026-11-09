package com.tapflow.api.merchants

import com.tapflow.api.auth.Identity
import com.tapflow.api.auth.invalid
import com.tapflow.api.db.Database
import com.tapflow.api.payments.PaymentRepository
import com.tapflow.api.payments.PaymentSummary
import java.sql.Connection
import java.sql.Timestamp
import java.time.*
import java.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class DailyBucket(val date: String, val approvedCount: Long, val grossApprovedMinor: Long)

@Serializable
data class Dashboard(
    val approvedCount: Long,
    val declinedCount: Long,
    val grossApprovedMinor: Long,
    val totalRefundedMinor: Long,
    val netApprovedMinor: Long,
    val from: String,
    val to: String,
    val dailyBuckets: List<DailyBucket>,
    val recentPayments: List<PaymentSummary>,
    val currency: String = "AUD",
    val demo: Boolean = true,
)

@Serializable
data class AuditEvent(
    val id: String,
    val actorUserId: String?,
    val merchantId: String,
    val eventType: String,
    val resourceType: String,
    val resourceId: String?,
    val requestId: String,
    val createdAt: String,
    val details: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class AuditPage(val items: List<AuditEvent>, val nextCursor: String?, val demo: Boolean = true)

class ReportsService(private val db: Database, clock: Clock = Clock.systemUTC()) {
    private val clock = Clock.tick(clock, Duration.ofNanos(1_000))

    private fun time(raw: String): Instant =
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

    private fun range(from: String?, to: String?): Pair<Instant, Instant> {
        val end = to?.let(::time) ?: clock.instant()
        val start = from?.let(::time) ?: end.minus(Duration.ofDays(30))
        if (start >= end || Duration.between(start, end) > Duration.ofDays(366)) invalid()
        return start to end
    }

    suspend fun dashboard(
        identity: Identity,
        merchant: UUID,
        from: String?,
        to: String?,
    ): Dashboard {
        val (start, end) = range(from, to)
        return db.query { c ->
            c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            MerchantAccess(db).require(c, identity.userId, merchant)
            val totals =
                c.prepareStatement(
                        """select count(*) filter(where p.status='APPROVED'),count(*) filter(where p.status='DECLINED'),
                coalesce(sum(p.amount_minor) filter(where p.status='APPROVED'),0),
                coalesce(sum((select coalesce(sum(r.amount_minor),0) from refunds r where r.payment_intent_id=p.id and r.merchant_id=p.merchant_id)) filter(where p.status='APPROVED'),0)
                from payment_intents p where p.merchant_id=? and p.authorised_at>=? and p.authorised_at<?"""
                    )
                    .use {
                        it.setObject(1, merchant)
                        it.setTimestamp(2, Timestamp.from(start))
                        it.setTimestamp(3, Timestamp.from(end))
                        it.executeQuery().use { r ->
                            r.next()
                            LongArray(4) { i -> r.getLong(i + 1) }
                        }
                    }
            val byDay =
                c.prepareStatement(
                        "select (authorised_at at time zone 'UTC')::date as day,count(*),sum(amount_minor) from payment_intents where merchant_id=? and status='APPROVED' and authorised_at>=? and authorised_at<? group by day order by day"
                    )
                    .use {
                        it.setObject(1, merchant)
                        it.setTimestamp(2, Timestamp.from(start))
                        it.setTimestamp(3, Timestamp.from(end))
                        it.executeQuery().use { r ->
                            buildMap {
                                while (r.next()) put(
                                    r.getDate(1).toLocalDate(),
                                    DailyBucket(r.getString(1), r.getLong(2), r.getLong(3)),
                                )
                            }
                        }
                    }
            val days =
                generateSequence(start.atOffset(ZoneOffset.UTC).toLocalDate()) { it.plusDays(1) }
                    .takeWhile { it.atStartOfDay().toInstant(ZoneOffset.UTC) < end }
                    .map { byDay[it] ?: DailyBucket(it.toString(), 0, 0) }
                    .toList()
            val recent = PaymentRepository().recent(c, merchant, start, end, clock.instant())
            Dashboard(
                totals[0],
                totals[1],
                totals[2],
                totals[3],
                totals[2] - totals[3],
                start.toString(),
                end.toString(),
                days,
                recent,
            )
        }
    }

    suspend fun audit(
        identity: Identity,
        merchant: UUID,
        limit: Int,
        cursor: String?,
        from: String?,
        to: String?,
        eventType: String?,
    ): AuditPage {
        if (
            limit !in 1..100 ||
                (eventType != null && !eventType.matches(Regex("[a-z][a-z0-9_.]{0,79}")))
        )
            invalid()
        val (start, end) = range(from, to)
        val after = cursor?.let {
            try {
                require(it.length in 1..512)
                val parts = String(Base64.getUrlDecoder().decode(it), Charsets.US_ASCII).split('|')
                require(parts.size == 3 && parts[0] == merchant.toString())
                val stamp = time(parts[1])
                val id = UUID.fromString(parts[2])
                require(id.toString() == parts[2])
                stamp to id
            } catch (_: Exception) {
                invalid()
            }
        }
        return db.query { c ->
            MerchantAccess(db).require(c, identity.userId, merchant, owner = true)
            val rows =
                c.prepareStatement(
                        "select id,actor_user_id,merchant_id,event_type,resource_type,resource_id,request_id,created_at from audit_events where merchant_id=? and created_at>=? and created_at<?" +
                            (if (eventType != null) " and event_type=?" else "") +
                            (if (after != null) " and (created_at,id)<(?,?)" else "") +
                            " order by created_at desc,id desc limit ?"
                    )
                    .use {
                        var n = 1
                        it.setObject(n++, merchant)
                        it.setTimestamp(n++, Timestamp.from(start))
                        it.setTimestamp(n++, Timestamp.from(end))
                        if (eventType != null) it.setString(n++, eventType)
                        if (after != null) {
                            it.setTimestamp(n++, Timestamp.from(after.first))
                            it.setObject(n++, after.second)
                        }
                        it.setInt(n, limit + 1)
                        it.executeQuery().use { r ->
                            buildList {
                                while (r.next()) add(
                                    AuditEvent(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getString(4),
                                        r.getString(5),
                                        r.getString(6),
                                        r.getString(7),
                                        r.getTimestamp(8).toInstant().toString(),
                                    )
                                )
                            }
                        }
                    }
            val page = rows.take(limit)
            val next =
                if (rows.size > limit)
                    page.last().let {
                        Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                "$merchant|${it.createdAt}|${it.id}".toByteArray(Charsets.US_ASCII)
                            )
                    }
                else null
            AuditPage(page, next)
        }
    }
}
