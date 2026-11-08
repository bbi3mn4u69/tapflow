package com.tapflow.protocol

import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProtocolTest {
    private val fixture =
        Json.parseToJsonElement(
                checkNotNull(javaClass.getResourceAsStream("/apdu-v1.json"))
                    .bufferedReader()
                    .readText()
            )
            .jsonObject

    private fun bytes(key: String) =
        HexFormat.of().parseHex(fixture.getValue(key).jsonPrimitive.content)

    private fun value(key: String) = fixture.getValue(key).jsonPrimitive.content

    private fun challenge() =
        Challenge(
            UUID.fromString(value("intentId")),
            UUID.fromString(value("merchantId")),
            2500,
            bytes("nonceHex"),
            value("expiresAtEpochMillis").toLong(),
        )

    private val card
        get() = UUID.fromString(value("cardId"))

    private val publicKey
        get() = Base64.getDecoder().decode(value("publicKeySpkiBase64"))

    private val signature
        get() = Base64.getDecoder().decode(value("signatureDerBase64"))

    @Test
    fun `exact proprietary SELECT returns version one and success`() {
        assertArrayEquals(bytes("selectRequestHex"), ApduCodec.selectCommand())
        assertArrayEquals(
            bytes("selectResponseHex"),
            ApduCodec.selectResponse(bytes("selectRequestHex")),
        )
    }

    @Test
    fun `challenge and canonical bytes match independently generated fixture`() {
        assertArrayEquals(bytes("challengeRequestHex"), ChallengeCodec.encode(challenge()))
        assertArrayEquals(bytes("canonicalHex"), ChallengeCodec.canonical(challenge(), card))
        assertArrayEquals(bytes("signCommandHex"), ApduCodec.signCommand(challenge()))
        val decoded = ChallengeCodec.decode(bytes("challengeRequestHex"))
        assertEquals(UUID.fromString("00112233-4455-4677-8899-aabbccddeeff"), decoded.intentId)
        assertEquals(2500, decoded.amountMinor)
        assertArrayEquals(bytes("nonceHex"), decoded.nonce)
    }

    @Test
    fun `wrong aid unsupported instruction and truncated frames fail with safe status words`() {
        val wrong = bytes("selectRequestHex").also { it[5] = 1 }
        assertArrayEquals(byteArrayOf(0x6a, 0x82.toByte()), ApduCodec.selectResponse(wrong))
        for (size in 0 until 15) assertArrayEquals(
            byteArrayOf(0x6a, 0x80.toByte()),
            ApduCodec.selectResponse(bytes("selectRequestHex").copyOf(size)),
        )
        assertArrayEquals(
            byteArrayOf(0x6d, 0),
            ApduCodec.selectResponse(byteArrayOf(0x80.toByte(), 0x21, 0, 0)),
        )
        assertTrue(ApduCodec.parse(ByteArray(10000)) is ApduCodec.Command.Rejected)
    }

    @Test
    fun `invalid version amount currency length expiry and trailing bytes are rejected`() {
        for ((offset, value) in listOf(0 to 2, 33 to 0x7f, 41 to 0x55, 60 to 0x80)) {
            val command = bytes("signCommandHex").also { it[offset + 5] = value.toByte() }
            assertTrue(ApduCodec.parse(command) is ApduCodec.Command.Rejected)
        }
        val zero = bytes("signCommandHex").also { for (i in 38..45) it[i] = 0 }
        assertTrue(ApduCodec.parse(zero) is ApduCodec.Command.Rejected)
        assertTrue(
            ApduCodec.parse(bytes("signCommandHex") + byteArrayOf(0)) is ApduCodec.Command.Rejected
        )
        assertTrue(
            ApduCodec.parse(bytes("signCommandHex").also { it[4] = 67 })
                is ApduCodec.Command.Rejected
        )
    }

    @Test
    fun `fixture signature verifies and every signed region is bound`() {
        assertTrue(SignatureVerifier.verify(challenge(), card, publicKey, signature))
        for (offset in listOf(1, 17, 40, 42, 44, 60, 67)) {
            val changed =
                bytes("challengeRequestHex").also {
                    it[offset] = (it[offset].toInt() xor 1).toByte()
                }
            val decoded = runCatching { ChallengeCodec.decode(changed) }.getOrNull()
            assertTrue(
                decoded == null || !SignatureVerifier.verify(decoded, card, publicKey, signature)
            )
        }
        assertFalse(SignatureVerifier.verify(challenge(), UUID.randomUUID(), publicKey, signature))
        assertFalse(
            SignatureVerifier.verify(
                challenge(),
                card,
                publicKey.also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
                signature,
            )
        )
        assertFalse(
            SignatureVerifier.verify(challenge(), card, publicKey, signature + byteArrayOf(0))
        )
        assertFalse(SignatureVerifier.verify(challenge(), card, publicKey, ByteArray(10000)))
    }

    @Test
    fun `proof framing matches fixture and rejects malformed lengths and der`() {
        assertArrayEquals(bytes("signResponseHex"), ApduCodec.signResponse(card, signature))
        val proof = ApduCodec.decodeProof(bytes("signResponseHex"))
        assertEquals(card, proof.cardId)
        assertArrayEquals(signature, proof.signature)
        for (bad in
            listOf(
                byteArrayOf(),
                bytes("signResponseHex").copyOf(20),
                bytes("signResponseHex").also { it[17] = 100 },
                bytes("signResponseHex").also { it[18] = 0 },
            )) {
            assertThrows(IllegalArgumentException::class.java) { ApduCodec.decodeProof(bad) }
        }
        assertFalse(SignatureVerifier.isDer(byteArrayOf(0x30, 6, 2, 1, 0, 2, 1, 1)))
    }

    @Test
    fun `selection unlock and deactivation gate asynchronous responses`() {
        val session = HceSession()
        assertArrayEquals(
            ApduCodec.status(0x6985),
            (session.handle(bytes("signCommandHex"), true) as HceSession.Action.Reply).bytes,
        )
        session.handle(bytes("selectRequestHex"), true)
        val action = session.handle(bytes("signCommandHex"), true) as HceSession.Action.Sign
        session.deactivate()
        assertNull(session.complete(action.generation, bytes("signResponseHex")))
        assertArrayEquals(
            ApduCodec.status(0x6985),
            (session.handle(bytes("selectRequestHex"), false) as HceSession.Action.Reply).bytes,
        )
        session.handle(bytes("selectRequestHex"), true)
        val old = session.handle(bytes("signCommandHex"), true) as HceSession.Action.Sign
        session.handle(bytes("selectRequestHex"), true)
        assertNull(session.complete(old.generation, bytes("signResponseHex")))
        val current = session.handle(bytes("signCommandHex"), true) as HceSession.Action.Sign
        assertArrayEquals(
            bytes("signResponseHex"),
            session.complete(current.generation, bytes("signResponseHex")),
        )
        assertNull(session.complete(current.generation, bytes("signResponseHex")))
    }
}
