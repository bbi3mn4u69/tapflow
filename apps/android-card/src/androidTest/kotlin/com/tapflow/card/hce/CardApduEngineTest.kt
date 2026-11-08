package com.tapflow.card.hce

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import com.tapflow.androidauth.RefreshTokenStore
import com.tapflow.card.crypto.CardKeyManager
import com.tapflow.card.data.CardLocalStore
import com.tapflow.protocol.*
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CardApduEngineTest {
    @Test
    fun actualKeystoreSignsThroughTheEngineAndRejectsStaleOrSignedOutRequests() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val owner = UUID.randomUUID().toString()
        val local = CardLocalStore(context)
        val session = RefreshTokenStore(context)
        val keys = CardKeyManager()
        val binding = local.pending(owner, "Engine test")
        val id = UUID.randomUUID()
        val responses = LinkedBlockingQueue<ByteArray>()
        lateinit var engine: CardApduEngine
        try {
            session.save("test-only-refresh-not-sent-to-server", owner)
            keys.create(binding.alias)
            local.confirmed(binding, id.toString(), "ACTIVE")
            local.enable(owner)
            instrumentation.runOnMainSync {
                engine = CardApduEngine(context, { responses.offer(it) }, { true })
            }
            val challenge =
                Challenge(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    2500,
                    ByteArray(16) { it.toByte() },
                    System.currentTimeMillis() + 90000,
                )
            instrumentation.runOnMainSync {
                assertArrayEquals(
                    byteArrayOf(1, 0x90.toByte(), 0),
                    engine.command(ApduCodec.selectCommand()),
                )
                assertNull(engine.command(ApduCodec.signCommand(challenge)))
            }
            val proof = ApduCodec.decodeProof(checkNotNull(responses.poll(5, TimeUnit.SECONDS)))
            assertEquals(id, proof.cardId)
            assertTrue(
                SignatureVerifier.verify(
                    challenge,
                    id,
                    keys.publicKey(binding.alias),
                    proof.signature,
                )
            )
            instrumentation.runOnMainSync {
                engine.command(ApduCodec.selectCommand())
                engine.command(ApduCodec.signCommand(challenge))
                engine.deactivate()
            }
            assertNull(responses.poll(300, TimeUnit.MILLISECONDS))
            session.clear()
            instrumentation.runOnMainSync {
                engine.command(ApduCodec.selectCommand())
                engine.command(ApduCodec.signCommand(challenge))
            }
            assertArrayEquals(
                ApduCodec.status(ApduCodec.NOT_READY),
                responses.poll(5, TimeUnit.SECONDS),
            )
            // A new login must not reactivate a previous session's tapping permission.
            session.save("another-test-only-refresh", owner)
            assertNull(local.readyBinding())
        } finally {
            instrumentation.runOnMainSync { engine.close() }
            local.reset(owner)
            session.clear()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(binding.alias)
            }
        }
    }

    @Test
    fun installedHceServiceHasTheSystemPermissionAndCustomAid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val service =
            context.packageManager.getServiceInfo(
                ComponentName(context, TapFlowHostApduService::class.java),
                PackageManager.GET_META_DATA,
            )
        assertTrue(service.exported)
        assertEquals("android.permission.BIND_NFC_SERVICE", service.permission)
        val parser =
            service.loadXmlMetaData(
                context.packageManager,
                "android.nfc.cardemulation.host_apdu_service",
            )
        parser.use {
            var aid: String? = null
            while (it.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (
                    it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG &&
                        it.name == "host-apdu-service"
                ) {
                    assertEquals(
                        "true",
                        it.getAttributeValue(
                            "http://schemas.android.com/apk/res/android",
                            "requireDeviceUnlock",
                        ),
                    )
                }
                if (
                    it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG &&
                        it.name == "aid-filter"
                )
                    aid = it.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
            }
            assertEquals("F0544150464C4F5731", aid)
        }
    }
}
