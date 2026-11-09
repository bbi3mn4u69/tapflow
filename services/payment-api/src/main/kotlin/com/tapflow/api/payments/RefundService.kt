package com.tapflow.api.payments

import com.tapflow.api.auth.Identity
import com.tapflow.api.auth.invalid
import com.tapflow.api.common.*
import com.tapflow.api.db.Database
import com.tapflow.api.merchants.MerchantAccess
import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class RefundRequest(val amountMinor: Long, val reason: String)

@Serializable
data class RefundResult(
    val refundId: String,
    val paymentId: String,
    val amountMinor: Long,
    val currency: String = "AUD",
    val refundStatus: String,
    val totalRefundedMinor: Long,
    val demo: Boolean = true,
)

class RefundService(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val nextId: () -> UUID = UUID::randomUUID,
) {
    suspend fun create(
        identity: Identity,
        merchant: UUID,
        payment: UUID,
        request: RefundRequest,
        key: UUID,
        requestId: UUID,
    ): Idempotency.Response {
        if (
            request.amountMinor !in 1..1_000_000 ||
                request.reason !in setOf("CUSTOMER_REQUEST", "DUPLICATE", "OTHER")
        )
            invalid()
        val json = Json { encodeDefaults = true }
        val hash =
            MessageDigest.getInstance("SHA-256")
                .digest(json.encodeToString(request).toByteArray(Charsets.UTF_8))
        for (attempt in 0..2) try {
            return db.query { c ->
                MerchantAccess(db).require(c, identity.userId, merchant, owner = true, lock = true)
                Idempotency.execute(
                    c,
                    "refunds:${identity.userId}:$merchant:$payment",
                    key,
                    hash,
                    clock.instant(),
                    nextId,
                ) {
                    val repo = PaymentRepository()
                    val intent = repo.intent(c, merchant, payment)
                    if (intent.status != "APPROVED") conflict()
                    val total =
                        c.prepareStatement(
                                "select coalesce(sum(amount_minor),0) from refunds where payment_intent_id=? and merchant_id=?"
                            )
                            .use {
                                it.setObject(1, payment)
                                it.setObject(2, merchant)
                                it.executeQuery().use { r ->
                                    r.next()
                                    r.getLong(1)
                                }
                            }
                    if (request.amountMinor > intent.amount - total) conflict()
                    val wallet = repo.wallet(c, checkNotNull(intent.card))
                    val now = clock.instant()
                    val refund = nextId()
                    c.prepareStatement(
                            "insert into refunds(id,payment_intent_id,merchant_id,amount_minor,reason,created_by,created_at) values (?,?,?,?,?,?,?)"
                        )
                        .use {
                            it.setObject(1, refund)
                            it.setObject(2, payment)
                            it.setObject(3, merchant)
                            it.setLong(4, request.amountMinor)
                            it.setString(5, request.reason)
                            it.setObject(6, identity.userId)
                            it.setTimestamp(7, Timestamp.from(now))
                            it.executeUpdate()
                        }
                    val balance = Math.addExact(wallet.balance, request.amountMinor)
                    c.prepareStatement("update wallets set balance_minor=?,updated_at=? where id=?")
                        .use {
                            it.setLong(1, balance)
                            it.setTimestamp(2, Timestamp.from(now))
                            it.setObject(3, wallet.id)
                            check(it.executeUpdate() == 1)
                        }
                    c.prepareStatement(
                            "insert into wallet_ledger(id,wallet_id,payment_intent_id,refund_id,entry_type,amount_minor,balance_after_minor,created_at) values (?,?,?,?,'CREDIT',?,?,?)"
                        )
                        .use {
                            it.setObject(1, nextId())
                            it.setObject(2, wallet.id)
                            it.setObject(3, payment)
                            it.setObject(4, refund)
                            it.setLong(5, request.amountMinor)
                            it.setLong(6, balance)
                            it.setTimestamp(7, Timestamp.from(now))
                            it.executeUpdate()
                        }
                    c.prepareStatement(
                            "insert into audit_events(id,merchant_id,actor_user_id,event_type,resource_type,resource_id,request_id,created_at) values (?,?,?,'refund.created','refund',?,?,?)"
                        )
                        .use {
                            it.setObject(1, nextId())
                            it.setObject(2, merchant)
                            it.setObject(3, identity.userId)
                            it.setObject(4, refund)
                            it.setObject(5, requestId)
                            it.setTimestamp(6, Timestamp.from(now))
                            it.executeUpdate()
                        }
                    val refunded = total + request.amountMinor
                    Idempotency.Response(
                        201,
                        json
                            .encodeToJsonElement(
                                RefundResult(
                                    refund.toString(),
                                    payment.toString(),
                                    request.amountMinor,
                                    refundStatus =
                                        if (refunded == intent.amount) "FULL" else "PARTIAL",
                                    totalRefundedMinor = refunded,
                                )
                            )
                            .jsonObject,
                    )
                }
            }
        } catch (failure: SQLException) {
            if (failure.sqlState !in setOf("40P01", "40001") || attempt == 2) throw failure
            delay(25L * (attempt + 1))
        }
        error("Unreachable refund retry")
    }

    private fun conflict(): Nothing =
        throw DomainError(
            HttpStatusCode.Conflict,
            "REFUND_EXCEEDS_REMAINING",
            "Refresh the payment and check its remaining refundable amount.",
        )
}
