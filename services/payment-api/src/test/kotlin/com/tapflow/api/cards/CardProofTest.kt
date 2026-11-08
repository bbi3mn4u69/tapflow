package com.tapflow.api.cards

import com.tapflow.protocol.*
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CardProofTest {
    @Test
    fun `server reconstructs fixture fields and verifies only the exact card and challenge`() {
        val fixture =
            Json.parseToJsonElement(
                    checkNotNull(javaClass.getResourceAsStream("/apdu-v1.json"))
                        .bufferedReader()
                        .readText()
                )
                .jsonObject
        fun field(key: String) = fixture.getValue(key).jsonPrimitive.content
        val challenge =
            Challenge(
                UUID.fromString(field("intentId")),
                UUID.fromString(field("merchantId")),
                2500,
                HexFormat.of().parseHex(field("nonceHex")),
                field("expiresAtEpochMillis").toLong(),
            )
        val key = Base64.getDecoder().decode(field("publicKeySpkiBase64"))
        val signature = Base64.getDecoder().decode(field("signatureDerBase64"))
        val card = UUID.fromString(field("cardId"))
        assertTrue(SignatureVerifier.verify(challenge, card, key, signature))
        val other =
            Challenge(
                UUID.randomUUID(),
                challenge.merchantId,
                2500,
                challenge.nonce,
                challenge.expiresAtEpochMillis,
            )
        assertFalse(SignatureVerifier.verify(other, card, key, signature))
    }
}
