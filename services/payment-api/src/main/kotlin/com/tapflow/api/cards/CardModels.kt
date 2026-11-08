package com.tapflow.api.cards

import kotlinx.serialization.Serializable

@Serializable
data class EnrollmentRequest(
    val label: String,
    val algorithm: String,
    val publicKeySpkiBase64: String,
) {
    override fun toString() = "EnrollmentRequest([public key redacted])"
}

@Serializable
data class Card(
    val cardId: String,
    val label: String,
    val status: String,
    val createdAt: String,
    val demo: Boolean = true,
)

@Serializable data class CardList(val items: List<Card>, val demo: Boolean = true)

@Serializable
data class Wallet(
    val cardId: String,
    val balanceMinor: Long,
    val currency: String = "AUD",
    val demo: Boolean = true,
)

@Serializable
data class WalletEntry(
    val id: String,
    val entryType: String,
    val amountMinor: Long,
    val balanceAfterMinor: Long,
    val merchantName: String?,
    val paymentId: String?,
    val refundId: String?,
    val createdAt: String,
)

@Serializable
data class ActivityPage(
    val items: List<WalletEntry>,
    val nextCursor: String?,
    val demo: Boolean = true,
)
