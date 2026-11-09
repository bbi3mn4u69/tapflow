package com.tapflow.pos.data

import com.tapflow.androidauth.MobileAuthRepository
import com.tapflow.androidauth.MobileUser
import com.tapflow.protocol.*
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class SaleView(
    val record: SavedSale,
    val challenge: Challenge?,
    val status: String,
    val receipt: JSONObject?,
)

class SaleRepository(
    private val user: MobileUser,
    private val auth: MobileAuthRepository,
    private val local: PosLocalStore,
) {
    private val mutex = Mutex()
    private val finals = setOf("APPROVED", "DECLINED", "CANCELLED", "EXPIRED")

    suspend fun start(terminal: PairedTerminal, amount: Long): SaleView = mutex.withLock {
        check(
            terminal.status == "ACTIVE" &&
                user.memberships.any { it.merchantId == terminal.merchantId }
        ) {
            "This terminal is unavailable."
        }
        val record =
            withContext(Dispatchers.IO) {
                local.prepareSale(user.id, terminal.merchantId, terminal.id, amount)
            }
        settle(record)
    }

    suspend fun recover(): SaleView? = mutex.withLock { current()?.let { settle(it) } }

    suspend fun saved(): SaleView? = current()?.let { view(it) }

    suspend fun authorise(proof: ApduCodec.Proof): SaleView = mutex.withLock {
        require(SignatureVerifier.isDer(proof.signature)) { "The card response is malformed." }
        var record = checkNotNull(current())
        check(record.finalStatus == null && !record.cancelRequested && record.intent != null)
        val body =
            JSONObject()
                .put("cardId", proof.cardId.toString())
                .put("signatureDerBase64", Base64.getEncoder().encodeToString(proof.signature))
        if (record.proof != null)
            check(
                record.proof.getString("cardId") == body.getString("cardId") &&
                    record.proof.getString("signatureDerBase64") ==
                        body.getString("signatureDerBase64")
            ) {
                "Recover the saved submission before reading another card."
            }
        record = record.copy(proof = body)
        save(record)
        submit(record)
    }

    suspend fun retrySubmission(): SaleView = mutex.withLock {
        val settled = settle(checkNotNull(current()))
        if (settled.status in finals) return@withLock settled
        check(settled.record.proof != null && !settled.record.cancelRequested) {
            "No saved submission is available."
        }
        submit(settled.record)
    }

    suspend fun cancel(): SaleView = mutex.withLock {
        val record = checkNotNull(current()).copy(cancelRequested = true)
        save(record)
        settle(record)
    }

    suspend fun acknowledge() = mutex.withLock {
        withContext(Dispatchers.IO) { local.acknowledgeSale(user.id) }
    }

    private suspend fun current() = withContext(Dispatchers.IO) { local.sale(user.id) }

    private suspend fun save(record: SavedSale) {
        withContext(Dispatchers.IO) { local.saveSale(record) }
    }

    private fun path(record: SavedSale) = "/merchants/${record.merchant}/payment-intents"

    private suspend fun settle(initial: SavedSale): SaleView {
        var record = initial
        check(user.memberships.any { it.merchantId == record.merchant }) {
            "This payment belongs to an unavailable workspace."
        }
        if (record.intent == null) {
            val created =
                auth.request(
                    path(record),
                    JSONObject()
                        .put("terminalId", record.terminal)
                        .put("amountMinor", record.amount)
                        .put("currency", "AUD"),
                    record.createKey,
                )
            validateIntent(record, created)
            record = record.copy(intent = created)
            save(record)
        }
        val id = checkNotNull(record.intent).getString("id")
        var latest = auth.request("${path(record)}/$id")
        validateIntent(record, latest)
        if (record.cancelRequested && latest.getString("status") == "AWAITING_TAP") {
            try {
                auth.request("${path(record)}/$id/cancel", JSONObject())
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
            }
            latest = auth.request("${path(record)}/$id")
            validateIntent(record, latest)
        }
        val status = latest.getString("status")
        record =
            record.copy(
                intent = latest,
                finalStatus = status.takeIf { it in finals },
                proof = if (status in finals) null else record.proof,
            )
        save(record)
        if (status in setOf("APPROVED", "DECLINED")) {
            val receipt =
                try {
                    auth.request("/merchants/${record.merchant}/payments/$id").also {
                        validateReceipt(record, it, detail = true)
                        check(it.getString("status") == status)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    record.receipt
                }
            record = record.copy(receipt = receipt)
            save(record)
        }
        return view(record)
    }

    private suspend fun submit(record: SavedSale): SaleView {
        val id = checkNotNull(record.intent).getString("id")
        val body =
            JSONObject(checkNotNull(record.proof).toString()).put("terminalId", record.terminal)
        val result =
            try {
                auth.request("${path(record)}/$id/authorise", body, record.authoriseKey)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val recovered = settle(record)
                if (recovered.status in finals) return recovered
                throw failure
            }
        validateReceipt(record, result, detail = false)
        val resolved =
            record.copy(proof = null, finalStatus = result.getString("status"), receipt = result)
        save(resolved)
        return view(resolved)
    }

    private fun view(record: SavedSale): SaleView {
        val intent = record.intent
        val challenge =
            if (intent != null) {
                validateIntent(record, intent)
                val raw = intent.getJSONObject("challenge")
                Challenge(
                    UUID.fromString(intent.getString("id")),
                    UUID.fromString(record.merchant),
                    record.amount,
                    Base64.getDecoder().decode(raw.getString("nonceBase64")),
                    integer(raw, "expiresAtEpochMillis"),
                )
            } else null
        return SaleView(
            record,
            challenge,
            record.finalStatus ?: intent?.getString("status") ?: "CREATING",
            record.receipt,
        )
    }

    private fun integer(json: JSONObject, key: String): Long {
        val value = json.get(key)
        check(value is Int || value is Long) {
            "The server returned an invalid amount or timestamp."
        }
        return (value as Number).toLong()
    }

    private fun validateIntent(record: SavedSale, json: JSONObject) {
        check(
            json.getBoolean("demo") &&
                json.getString("merchantId") == record.merchant &&
                json.getString("terminalId") == record.terminal &&
                integer(json, "amountMinor") == record.amount &&
                json.getString("currency") == "AUD"
        ) {
            "The server returned a different payment. Check its status before starting another."
        }
        UUID.fromString(json.getString("id"))
        record.intent?.let {
            check(it.getString("id") == json.getString("id")) {
                "The payment ID changed unexpectedly."
            }
        }
        check(json.getString("status") in finals + "AWAITING_TAP")
        val challenge = json.getJSONObject("challenge")
        check(
            challenge.getInt("protocolVersion") == 1 &&
                Base64.getDecoder().decode(challenge.getString("nonceBase64")).size == 16
        )
        val expiry = Instant.parse(json.getString("expiresAt"))
        check(
            expiry.nano % 1_000_000 == 0 &&
                expiry.toEpochMilli() == integer(challenge, "expiresAtEpochMillis")
        )
    }

    private fun validateReceipt(record: SavedSale, json: JSONObject, detail: Boolean) {
        check(
            json.getBoolean("demo") &&
                json.getString("paymentId") == checkNotNull(record.intent).getString("id") &&
                integer(json, "amountMinor") == record.amount &&
                json.getString("currency") == "AUD"
        )
        val status = json.getString("status")
        val decision = json.getString("decisionCode")
        check(
            (status == "APPROVED" && decision == "APPROVED" && !json.isNull("receiptNumber")) ||
                (status == "DECLINED" && decision in setOf("CARD_BLOCKED", "INSUFFICIENT_FUNDS"))
        )
        Instant.parse(json.getString("authorisedAt"))
        if (detail)
            check(
                json.getString("merchantId") == record.merchant &&
                    json.getString("terminalId") == record.terminal
            )
    }
}
