package com.tapflow.api.cards

import com.tapflow.api.auth.unauthorized
import com.tapflow.api.common.DomainError
import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class CardRepository {
    fun customer(c: Connection, user: UUID, lock: Boolean = false) {
        c.prepareStatement(
                "select account_type from users where id=? and active" +
                    if (lock) " for update" else ""
            )
            .use {
                it.setObject(1, user)
                it.executeQuery().use { rows ->
                    if (!rows.next()) unauthorized()
                    if (rows.getString(1) != "CUSTOMER")
                        throw DomainError(
                            HttpStatusCode.Forbidden,
                            "INSUFFICIENT_ROLE",
                            "A customer account is required.",
                        )
                }
            }
    }

    fun card(c: Connection, user: UUID, id: UUID, lock: Boolean = false): Card =
        c.prepareStatement(
                "select * from virtual_cards where id=? and owner_user_id=?" +
                    if (lock) " for update" else ""
            )
            .use {
                it.setObject(1, id)
                it.setObject(2, user)
                it.executeQuery().use { r ->
                    if (!r.next()) missing()
                    r.card()
                }
            }

    fun list(c: Connection, user: UUID): List<Card> =
        c.prepareStatement(
                "select * from virtual_cards where owner_user_id=? order by created_at desc,id desc limit 100"
            )
            .use {
                it.setObject(1, user)
                it.executeQuery().use { r -> buildList { while (r.next()) add(r.card()) } }
            }

    fun existing(c: Connection, user: UUID, key: ByteArray, label: String): Card? =
        c.prepareStatement("select * from virtual_cards where public_key_spki=?").use {
            it.setBytes(1, key)
            it.executeQuery().use { r ->
                if (!r.next()) null
                else {
                    if (
                        r.getObject("owner_user_id", UUID::class.java) != user ||
                            r.getString("label") != label
                    )
                        throw DomainError(
                            HttpStatusCode.Conflict,
                            "CARD_ALREADY_ENROLLED",
                            "This key is already enrolled.",
                        )
                    r.card()
                }
            }
        }

    fun counts(c: Connection, user: UUID, since: Instant): Pair<Int, Int> =
        c.prepareStatement(
                "select count(*),count(*) filter (where created_at>=?) from virtual_cards where owner_user_id=?"
            )
            .use {
                it.setTimestamp(1, Timestamp.from(since))
                it.setObject(2, user)
                it.executeQuery().use { r ->
                    r.next()
                    r.getInt(1) to r.getInt(2)
                }
            }

    fun insert(
        c: Connection,
        user: UUID,
        id: UUID,
        key: ByteArray,
        label: String,
        now: Instant,
    ): Card? =
        c.prepareStatement(
                "insert into virtual_cards(id,owner_user_id,label,public_key_spki,created_at) values (?,?,?,?,?) on conflict(public_key_spki) do nothing returning *"
            )
            .use {
                it.setObject(1, id)
                it.setObject(2, user)
                it.setString(3, label)
                it.setBytes(4, key)
                it.setTimestamp(5, Timestamp.from(now))
                it.executeQuery().use { r -> if (r.next()) r.card() else null }
            }

    fun fund(c: Connection, card: UUID, wallet: UUID, ledger: UUID, amount: Long, now: Instant) {
        c.prepareStatement(
                "insert into wallets(id,card_id,balance_minor,created_at,updated_at) values (?,?,?,?,?)"
            )
            .use {
                it.setObject(1, wallet)
                it.setObject(2, card)
                it.setLong(3, amount)
                it.setTimestamp(4, Timestamp.from(now))
                it.setTimestamp(5, Timestamp.from(now))
                it.executeUpdate()
            }
        c.prepareStatement(
                "insert into wallet_ledger(id,wallet_id,entry_type,amount_minor,balance_after_minor,created_at) values (?,?,'DEMO_FUNDING',?,?,?)"
            )
            .use {
                it.setObject(1, ledger)
                it.setObject(2, wallet)
                it.setLong(3, amount)
                it.setLong(4, amount)
                it.setTimestamp(5, Timestamp.from(now))
                it.executeUpdate()
            }
    }

    fun wallet(c: Connection, user: UUID, card: UUID): Wallet =
        c.prepareStatement(
                "select w.balance_minor,w.currency from wallets w join virtual_cards v on v.id=w.card_id where v.id=? and v.owner_user_id=?"
            )
            .use {
                it.setObject(1, card)
                it.setObject(2, user)
                it.executeQuery().use { r ->
                    if (!r.next()) missing()
                    Wallet(card.toString(), r.getLong(1), r.getString(2))
                }
            }

    fun disable(c: Connection, user: UUID, id: UUID): Card =
        c.prepareStatement(
                "update virtual_cards set status='BLOCKED' where id=? and owner_user_id=? returning *"
            )
            .use {
                it.setObject(1, id)
                it.setObject(2, user)
                it.executeQuery().use { r ->
                    if (!r.next()) missing()
                    r.card()
                }
            }

    fun activity(
        c: Connection,
        user: UUID,
        card: UUID,
        limit: Int,
        cursor: Pair<Instant, UUID>?,
    ): List<WalletEntry> {
        val predicate = if (cursor != null) " and (l.created_at,l.id)<(?,?)" else ""
        return c.prepareStatement(
                """
            select l.*,m.name as merchant_name from wallet_ledger l
            join wallets w on w.id=l.wallet_id join virtual_cards v on v.id=w.card_id
            left join refunds r on r.id=l.refund_id
            left join payment_intents p on p.id=coalesce(l.payment_intent_id,r.payment_intent_id)
            left join merchants m on m.id=p.merchant_id
            where v.id=? and v.owner_user_id=?$predicate order by l.created_at desc,l.id desc limit ?
        """
                    .trimIndent()
            )
            .use {
                it.setObject(1, card)
                it.setObject(2, user)
                var index = 3
                if (cursor != null) {
                    it.setTimestamp(index++, Timestamp.from(cursor.first))
                    it.setObject(index++, cursor.second)
                }
                it.setInt(index, limit + 1)
                it.executeQuery().use { r ->
                    buildList {
                        while (r.next()) add(
                            WalletEntry(
                                r.getString("id"),
                                r.getString("entry_type"),
                                r.getLong("amount_minor"),
                                r.getLong("balance_after_minor"),
                                r.getString("merchant_name"),
                                r.getString("payment_intent_id"),
                                r.getString("refund_id"),
                                r.getTimestamp("created_at").toInstant().toString(),
                            )
                        )
                    }
                }
            }
    }

    fun audit(
        c: Connection,
        id: UUID,
        user: UUID,
        card: UUID,
        event: String,
        requestId: UUID,
        now: Instant,
    ) {
        c.prepareStatement(
                "insert into audit_events(id,actor_user_id,event_type,resource_type,resource_id,request_id,created_at) values (?,?,?,'card',?,?,?)"
            )
            .use {
                it.setObject(1, id)
                it.setObject(2, user)
                it.setString(3, event)
                it.setObject(4, card)
                it.setObject(5, requestId)
                it.setTimestamp(6, Timestamp.from(now))
                it.executeUpdate()
            }
    }

    private fun ResultSet.card() =
        Card(
            getString("id"),
            getString("label"),
            getString("status"),
            getTimestamp("created_at").toInstant().toString(),
        )

    private fun missing(): Nothing =
        throw DomainError(
            HttpStatusCode.NotFound,
            "RESOURCE_NOT_FOUND",
            "The resource was not found.",
        )
}
