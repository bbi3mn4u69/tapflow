package com.tapflow.protocol

import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReaderExchangeTest {
    private val f =
        Json.parseToJsonElement(
                checkNotNull(javaClass.getResourceAsStream("/apdu-v1.json"))
                    .bufferedReader()
                    .readText()
            )
            .jsonObject

    private fun raw(name: String) = HexFormat.of().parseHex(f.getValue(name).jsonPrimitive.content)

    @Test
    fun `reader exchanges exactly SELECT then challenge and decodes an independently signed proof`() {
        val challenge = ChallengeCodec.decode(raw("challengeRequestHex"))
        val requests = mutableListOf<ByteArray>()
        val proof =
            ReaderExchange.read(challenge) { command ->
                requests.add(command.copyOf())
                when (requests.size) {
                    1 -> {
                        assertArrayEquals(raw("selectRequestHex"), command)
                        raw("selectResponseHex")
                    }
                    2 -> {
                        assertArrayEquals(raw("signCommandHex"), command)
                        raw("signResponseHex")
                    }
                    else -> error("Duplicate exchange")
                }
            }
        assertEquals(2, requests.size)
        assertEquals(UUID.fromString(f.getValue("cardId").jsonPrimitive.content), proof.cardId)
        assertTrue(
            SignatureVerifier.verify(
                challenge,
                proof.cardId,
                Base64.getDecoder().decode(f.getValue("publicKeySpkiBase64").jsonPrimitive.content),
                proof.signature,
            )
        )
    }

    @Test
    fun `failed or incompatible select never sends a signing command`() {
        for (response in
            listOf(
                byteArrayOf(2, 0x90.toByte(), 0),
                byteArrayOf(0x69, 0x85.toByte()),
                byteArrayOf(1, 0x90.toByte(), 0, 0),
            )) {
            var calls = 0
            assertThrows<IllegalArgumentException> {
                ReaderExchange.read(ChallengeCodec.decode(raw("challengeRequestHex"))) {
                    calls++
                    response
                }
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `malformed proof is rejected instead of being shown as a successful read`() {
        var calls = 0
        assertThrows<IllegalArgumentException> {
            ReaderExchange.read(ChallengeCodec.decode(raw("challengeRequestHex"))) {
                if (++calls == 1) raw("selectResponseHex")
                else raw("signResponseHex") + byteArrayOf(0)
            }
        }
    }
}
