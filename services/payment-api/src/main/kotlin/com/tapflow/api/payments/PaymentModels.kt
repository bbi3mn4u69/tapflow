package com.tapflow.api.payments

import com.tapflow.protocol.Challenge
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CreateIntentRequest(
    val terminalId: String,
    val amountMinor: Long,
    val currency: String,
    val orderReference: String? = null,
)

@Serializable
data class AuthoriseRequest(
    val terminalId: String,
    val cardId: String,
    val signatureDerBase64: String,
) {
    override fun toString() = "AuthoriseRequest([redacted])"
}

@Serializable
data class IntentChallenge(
    val protocolVersion: Int = 1,
    val nonceBase64: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class PaymentIntent(
    val id: String,
    val merchantId: String,
    val terminalId: String,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val challenge: IntentChallenge,
    val expiresAt: String,
    val createdAt: String,
    val orderReference: String? = null,
    val decisionCode: String? = null,
    val demo: Boolean = true,
)

@Serializable
data class AuthorisationResult(
    val paymentId: String,
    val status: String,
    val decisionCode: String,
    val amountMinor: Long,
    val currency: String,
    val refundedAmountMinor: Long = 0,
    val refundStatus: String = "NONE",
    val merchantName: String,
    val receiptNumber: String?,
    val authorisedAt: String,
    val demo: Boolean = true,
)

@Serializable
data class PaymentRefund(
    val refundId: String,
    val paymentId: String,
    val amountMinor: Long,
    val reason: String,
    val createdAt: String,
)

@Serializable
data class PaymentSummary(
    val paymentId: String,
    val merchantId: String,
    val terminalId: String,
    val terminalLabel: String,
    val amountMinor: Long,
    val refundedAmountMinor: Long,
    val netAmountMinor: Long,
    val refundStatus: String,
    val status: String,
    val decisionCode: String?,
    val currency: String,
    val receiptNumber: String?,
    val createdAt: String,
    val authorisedAt: String?,
    val demo: Boolean = true,
)

@Serializable
data class PaymentPage(
    val items: List<PaymentSummary>,
    val nextCursor: String?,
    val demo: Boolean = true,
)

@Serializable
data class PaymentDetail(
    val paymentId: String,
    val merchantId: String,
    val terminalId: String,
    val terminalLabel: String,
    val amountMinor: Long,
    val refundedAmountMinor: Long,
    val netAmountMinor: Long,
    val refundStatus: String,
    val status: String,
    val decisionCode: String?,
    val currency: String,
    val receiptNumber: String?,
    val createdAt: String,
    val authorisedAt: String?,
    val merchantName: String,
    val cardAlias: String?,
    val refunds: List<PaymentRefund>,
    val demo: Boolean = true,
)

internal class StoredIntent(
    val id: UUID,
    val merchant: UUID,
    val terminal: UUID,
    val card: UUID?,
    val amount: Long,
    val currency: String,
    val nonce: ByteArray,
    val expiry: Instant,
    val created: Instant,
    val status: String,
    val order: String?,
    val decision: String?,
    val proofHash: ByteArray?,
    val receipt: String?,
    val authorisedAt: Instant?,
) {
    fun challenge() = Challenge(id, merchant, amount, nonce, expiry.toEpochMilli(), currency)

    fun wire() =
        PaymentIntent(
            id.toString(),
            merchant.toString(),
            terminal.toString(),
            amount,
            currency,
            status,
            IntentChallenge(
                nonceBase64 = Base64.getEncoder().encodeToString(nonce),
                expiresAtEpochMillis = expiry.toEpochMilli(),
            ),
            expiry.toString(),
            created.toString(),
            order,
            decision,
        )
}
