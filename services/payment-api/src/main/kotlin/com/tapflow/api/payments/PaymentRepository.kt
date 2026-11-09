package com.tapflow.api.payments

import com.tapflow.api.common.DomainError
import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class PaymentRepository {
    fun terminal(
        c: Connection,
        merchant: UUID,
        terminal: UUID,
        exclusive: Boolean = false,
    ): String =
        c.prepareStatement(
                "select status from terminals where id=? and merchant_id=?" +
                    if (exclusive) " for update" else " for share"
            )
            .use {
                it.setObject(1, terminal)
                it.setObject(2, merchant)
                it.executeQuery().use { r ->
                    if (!r.next()) missing()
                    r.getString(1)
                }
            }

    fun intent(c: Connection, merchant: UUID, id: UUID): StoredIntent =
        c.prepareStatement("select * from payment_intents where id=? and merchant_id=? for update")
            .use {
                it.setObject(1, id)
                it.setObject(2, merchant)
                it.executeQuery().use { r ->
                    if (!r.next()) missing()
                    r.intent()
                }
            }

    fun expirePending(c: Connection, merchant: UUID, terminal: UUID, now: Instant) {
        c.prepareStatement(
                "update payment_intents set status='EXPIRED',updated_at=? where merchant_id=? and terminal_id=? and status='AWAITING_TAP' and expires_at<=?"
            )
            .use {
                it.setTimestamp(1, Timestamp.from(now))
                it.setObject(2, merchant)
                it.setObject(3, terminal)
                it.setTimestamp(4, Timestamp.from(now))
                it.executeUpdate()
            }
        c.prepareStatement(
                "select id from payment_intents where merchant_id=? and terminal_id=? and status='AWAITING_TAP' limit 1"
            )
            .use {
                it.setObject(1, merchant)
                it.setObject(2, terminal)
                it.executeQuery().use { r ->
                    if (r.next()) conflict("Resolve the current payment before creating another.")
                }
            }
    }

    fun create(
        c: Connection,
        id: UUID,
        merchant: UUID,
        terminal: UUID,
        request: CreateIntentRequest,
        nonce: ByteArray,
        now: Instant,
    ): StoredIntent =
        c.prepareStatement(
                "insert into payment_intents(id,merchant_id,terminal_id,amount_minor,currency,challenge_nonce,expires_at,order_reference,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?) returning *"
            )
            .use {
                it.setObject(1, id)
                it.setObject(2, merchant)
                it.setObject(3, terminal)
                it.setLong(4, request.amountMinor)
                it.setString(5, request.currency)
                it.setBytes(6, nonce)
                it.setTimestamp(7, Timestamp.from(now.plusSeconds(90)))
                it.setString(8, request.orderReference)
                it.setTimestamp(9, Timestamp.from(now))
                it.setTimestamp(10, Timestamp.from(now))
                it.executeQuery().use { r ->
                    check(r.next())
                    r.intent()
                }
            }

    fun finish(
        c: Connection,
        intent: StoredIntent,
        status: String,
        now: Instant,
        card: UUID? = null,
        proof: ByteArray? = null,
        decision: String? = null,
        receipt: String? = null,
    ): StoredIntent =
        c.prepareStatement(
                "update payment_intents set status=?,updated_at=?,card_id=?,proof_sha256=?,decision_code=?,receipt_number=?,authorised_at=? where id=? and merchant_id=? and status='AWAITING_TAP' returning *"
            )
            .use {
                it.setString(1, status)
                it.setTimestamp(2, Timestamp.from(now))
                it.setObject(3, card)
                it.setBytes(4, proof)
                it.setString(5, decision)
                it.setString(6, receipt)
                it.setTimestamp(
                    7,
                    if (status in setOf("APPROVED", "DECLINED")) Timestamp.from(now) else null,
                )
                it.setObject(8, intent.id)
                it.setObject(9, intent.merchant)
                it.executeQuery().use { r ->
                    check(r.next())
                    r.intent()
                }
            }

    class Card(val key: ByteArray, val active: Boolean)

    fun card(c: Connection, id: UUID): Card =
        c.prepareStatement(
                "select v.public_key_spki,v.status,u.active,u.account_type from virtual_cards v join users u on u.id=v.owner_user_id where v.id=? for share of v"
            )
            .use {
                it.setObject(1, id)
                it.executeQuery().use { r ->
                    if (!r.next()) badProof()
                    Card(
                        r.getBytes(1),
                        r.getString(2) == "ACTIVE" &&
                            r.getBoolean(3) &&
                            r.getString(4) == "CUSTOMER",
                    )
                }
            }

    class Wallet(val id: UUID, val balance: Long)

    fun wallet(c: Connection, card: UUID): Wallet =
        c.prepareStatement(
                "select id,balance_minor,currency from wallets where card_id=? for update"
            )
            .use {
                it.setObject(1, card)
                it.executeQuery().use { r ->
                    if (!r.next() || r.getString(3) != "AUD") badProof()
                    Wallet(r.getObject(1, UUID::class.java), r.getLong(2))
                }
            }

    fun debit(c: Connection, wallet: Wallet, intent: StoredIntent, ledgerId: UUID, now: Instant) {
        val balance =
            c.prepareStatement(
                    "update wallets set balance_minor=balance_minor-?,updated_at=? where id=? and balance_minor>=? returning balance_minor"
                )
                .use {
                    it.setLong(1, intent.amount)
                    it.setTimestamp(2, Timestamp.from(now))
                    it.setObject(3, wallet.id)
                    it.setLong(4, intent.amount)
                    it.executeQuery().use { r ->
                        check(r.next())
                        r.getLong(1)
                    }
                }
        c.prepareStatement(
                "insert into wallet_ledger(id,wallet_id,payment_intent_id,entry_type,amount_minor,balance_after_minor,created_at) values (?,?,?,'DEBIT',?,?,?)"
            )
            .use {
                it.setObject(1, ledgerId)
                it.setObject(2, wallet.id)
                it.setObject(3, intent.id)
                it.setLong(4, intent.amount)
                it.setLong(5, balance)
                it.setTimestamp(6, Timestamp.from(now))
                it.executeUpdate()
            }
    }

    fun audit(
        c: Connection,
        id: UUID,
        user: UUID,
        intent: StoredIntent,
        event: String,
        requestId: UUID,
        now: Instant,
    ) {
        c.prepareStatement(
                "insert into audit_events(id,merchant_id,actor_user_id,event_type,resource_type,resource_id,request_id,created_at) values (?,?,?,?,'payment_intent',?,?,?)"
            )
            .use {
                it.setObject(1, id)
                it.setObject(2, intent.merchant)
                it.setObject(3, user)
                it.setString(4, event)
                it.setObject(5, intent.id)
                it.setObject(6, requestId)
                it.setTimestamp(7, Timestamp.from(now))
                it.executeUpdate()
            }
    }

    fun seen(c: Connection, merchant: UUID, terminal: UUID, now: Instant) {
        c.prepareStatement("update terminals set last_seen_at=? where id=? and merchant_id=?").use {
            it.setTimestamp(1, Timestamp.from(now))
            it.setObject(2, terminal)
            it.setObject(3, merchant)
            it.executeUpdate()
        }
    }

    fun summary(c: Connection, merchant: UUID, id: UUID, now: Instant): PaymentSummary =
        c.prepareStatement(summarySql + " where p.merchant_id=? and p.id=?").use {
            it.setTimestamp(1, Timestamp.from(now))
            it.setObject(2, merchant)
            it.setObject(3, id)
            it.executeQuery().use { r ->
                if (!r.next()) missing()
                r.summary()
            }
        }

    fun details(c: Connection, merchant: UUID, id: UUID, now: Instant): PaymentDetail {
        val summary = summary(c, merchant, id, now)
        val names =
            c.prepareStatement(
                    "select m.name,v.label from payment_intents p join merchants m on m.id=p.merchant_id left join virtual_cards v on v.id=p.card_id where p.id=? and p.merchant_id=?"
                )
                .use {
                    it.setObject(1, id)
                    it.setObject(2, merchant)
                    it.executeQuery().use { r ->
                        check(r.next())
                        r.getString(1) to r.getString(2)
                    }
                }
        val refunds =
            c.prepareStatement(
                    "select * from refunds where payment_intent_id=? and merchant_id=? order by created_at,id"
                )
                .use {
                    it.setObject(1, id)
                    it.setObject(2, merchant)
                    it.executeQuery().use { r ->
                        buildList {
                            while (r.next()) add(
                                PaymentRefund(
                                    r.getString("id"),
                                    r.getString("payment_intent_id"),
                                    r.getLong("amount_minor"),
                                    r.getString("reason"),
                                    r.getTimestamp("created_at").toInstant().toString(),
                                )
                            )
                        }
                    }
                }
        return summary.run {
            PaymentDetail(
                paymentId,
                merchantId,
                terminalId,
                terminalLabel,
                amountMinor,
                refundedAmountMinor,
                netAmountMinor,
                refundStatus,
                status,
                decisionCode,
                currency,
                receiptNumber,
                createdAt,
                authorisedAt,
                names.first,
                names.second,
                refunds,
            )
        }
    }

    fun list(
        c: Connection,
        merchant: UUID,
        limit: Int,
        after: Pair<Instant, UUID>?,
        from: Instant,
        to: Instant,
        status: String?,
        now: Instant,
    ): List<PaymentSummary> {
        val cursor = if (after != null) " and (p.created_at,p.id)<(?,?)" else ""
        val filter =
            if (status != null)
                " and case when p.status='AWAITING_TAP' and p.expires_at<=? then 'EXPIRED' else p.status end=?"
            else ""
        return c.prepareStatement(
                summarySql +
                    " where p.merchant_id=? and p.created_at>=? and p.created_at<?$cursor$filter order by p.created_at desc,p.id desc limit ?"
            )
            .use {
                var index = 1
                it.setTimestamp(index++, Timestamp.from(now))
                it.setObject(index++, merchant)
                it.setTimestamp(index++, Timestamp.from(from))
                it.setTimestamp(index++, Timestamp.from(to))
                if (after != null) {
                    it.setTimestamp(index++, Timestamp.from(after.first))
                    it.setObject(index++, after.second)
                }
                if (status != null) {
                    it.setTimestamp(index++, Timestamp.from(now))
                    it.setString(index++, status)
                }
                it.setInt(index, limit + 1)
                it.executeQuery().use { r -> buildList { while (r.next()) add(r.summary()) } }
            }
    }

    private val summarySql =
        """
        select p.*,t.label as terminal_label,
                case when p.status='AWAITING_TAP' and p.expires_at<=? then 'EXPIRED' else p.status end as effective_status,
                coalesce((select sum(r.amount_minor) from refunds r where r.payment_intent_id=p.id and r.merchant_id=p.merchant_id),0) as refunded
                from payment_intents p join terminals t on t.id=p.terminal_id and t.merchant_id=p.merchant_id
        """
            .trimIndent()

    private fun ResultSet.summary(): PaymentSummary {
        val amount = getLong("amount_minor")
        val refunded = getLong("refunded")
        val status = getString("effective_status")
        return PaymentSummary(
            getString("id"),
            getString("merchant_id"),
            getString("terminal_id"),
            getString("terminal_label"),
            amount,
            refunded,
            if (status == "APPROVED") amount - refunded else 0,
            if (refunded == 0L) "NONE" else if (refunded < amount) "PARTIAL" else "FULL",
            status,
            getString("decision_code"),
            getString("currency"),
            getString("receipt_number"),
            getTimestamp("created_at").toInstant().toString(),
            getTimestamp("authorised_at")?.toInstant()?.toString(),
        )
    }

    private fun ResultSet.intent() =
        StoredIntent(
            getObject("id", UUID::class.java),
            getObject("merchant_id", UUID::class.java),
            getObject("terminal_id", UUID::class.java),
            getObject("card_id", UUID::class.java),
            getLong("amount_minor"),
            getString("currency"),
            getBytes("challenge_nonce"),
            getTimestamp("expires_at").toInstant(),
            getTimestamp("created_at").toInstant(),
            getString("status"),
            getString("order_reference"),
            getString("decision_code"),
            getBytes("proof_sha256"),
            getString("receipt_number"),
            getTimestamp("authorised_at")?.toInstant(),
        )

    private fun missing(): Nothing =
        throw DomainError(
            HttpStatusCode.NotFound,
            "RESOURCE_NOT_FOUND",
            "The resource was not found.",
        )

    fun badProof(): Nothing =
        throw DomainError(
            HttpStatusCode.BadRequest,
            "INVALID_CARD_PROOF",
            "The card response could not be verified.",
        )

    fun conflict(message: String = "The payment already has a different outcome."): Nothing =
        throw DomainError(HttpStatusCode.Conflict, "INVALID_PAYMENT_STATE", message)
}
