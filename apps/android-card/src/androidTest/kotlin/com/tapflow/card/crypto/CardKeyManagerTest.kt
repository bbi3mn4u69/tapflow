package com.tapflow.card.crypto

import com.tapflow.protocol.*
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CardKeyManagerTest {
    @Test
    fun generatesANonExportableP256KeyAndSignsCanonicalChallenges() {
        val alias = "tapflow-test-card-${UUID.randomUUID()}"
        val manager = CardKeyManager()
        try {
            manager.create(alias)
            val public = manager.publicKey(alias)
            assertEquals(256, SignatureVerifier.publicKey(public).params.curve.field.fieldSize)
            val key =
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
            assertNull(key.encoded)
            manager.create(alias)
            assertArrayEquals(public, manager.publicKey(alias))
            val challenge =
                Challenge(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    2500,
                    ByteArray(16) { it.toByte() },
                    1794103200000,
                )
            val card = UUID.randomUUID()
            val signature = manager.sign(alias, ChallengeCodec.canonical(challenge, card))
            assertTrue(SignatureVerifier.verify(challenge, card, public, signature))
            assertFalse(SignatureVerifier.verify(challenge, UUID.randomUUID(), public, signature))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
