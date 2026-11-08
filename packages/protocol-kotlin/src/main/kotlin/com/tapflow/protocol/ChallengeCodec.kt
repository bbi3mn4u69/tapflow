package com.tapflow.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class Challenge(
    val intentId: UUID,
    val merchantId: UUID,
    val amountMinor: Long,
    nonce: ByteArray,
    val expiresAtEpochMillis: Long,
    val currency: String = "AUD",
) {
    private val nonceValue = nonce.copyOf()
    val nonce: ByteArray
        get() = nonceValue.copyOf()

    init {
        require(amountMinor in 1..1_000_000 && currency == "AUD") { "Invalid amount or currency" }
        require(nonce.size == 16 && expiresAtEpochMillis > 0) { "Invalid nonce or expiry" }
    }
}

object ChallengeCodec {
    fun encode(value: Challenge): ByteArray =
        ByteBuffer.allocate(68)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(1)
                putUuid(value.intentId)
                putUuid(value.merchantId)
                putLong(value.amountMinor)
                put(value.currency.toByteArray(Charsets.US_ASCII))
                put(value.nonce)
                putLong(value.expiresAtEpochMillis)
            }
            .array()

    fun decode(bytes: ByteArray): Challenge {
        require(bytes.size == 68) { "Invalid challenge size" }
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).run {
            require(get().toInt() == 1) { "Unsupported protocol version" }
            val intent = getUuid()
            val merchant = getUuid()
            val amount = long
            val currency = ByteArray(3).also { get(it) }.toString(Charsets.US_ASCII)
            val nonce = ByteArray(16).also { get(it) }
            val expiry = long
            Challenge(intent, merchant, amount, nonce, expiry, currency)
        }
    }

    fun canonical(value: Challenge, cardId: UUID): ByteArray =
        "TAPFLOW-DEMO-V1\u0000".toByteArray(Charsets.US_ASCII) + encode(value) + uuidBytes(cardId)

    fun uuidBytes(id: UUID): ByteArray = ByteBuffer.allocate(16).apply { putUuid(id) }.array()

    internal fun ByteBuffer.putUuid(id: UUID) {
        putLong(id.mostSignificantBits)
        putLong(id.leastSignificantBits)
    }

    internal fun ByteBuffer.getUuid() = UUID(long, long)
}
