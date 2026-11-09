package com.tapflow.pos.payments

import android.os.ParcelFileDescriptor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tapflow.androidauth.*
import com.tapflow.pos.*
import com.tapflow.pos.data.*
import com.tapflow.protocol.*
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LivePaymentTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun committedPaymentSurvivesMissingOutcomeAndStatusOutageWithoutAnotherDebit() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tapflowLiveAuth") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("tapflow_pos", 0).edit().clear().commit()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Email").performTextInput("operator@example.test")
        compose.onNodeWithText("Password").performTextInput("instrumentation-only-passphrase")
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("Pair this terminal").fetchSemanticsNodes().isNotEmpty()
        }
        val api = MobileAuthApi(BuildConfig.API_BASE_URL, true)
        val owner = runBlocking {
            api.login("owner@example.test", "instrumentation-only-passphrase")
        }
        val merchant = owner.user.memberships.single().merchantId
        val code = runBlocking {
            api.resource(
                    "/merchants/$merchant/terminals/pairing-codes",
                    owner.access,
                    JSONObject(),
                    UUID.randomUUID().toString(),
                )
                .getString("pairingCode")
        }
        compose.onNodeWithText("Pairing code").performTextInput(code)
        compose.onNodeWithText("Terminal name").performTextClearance()
        compose.onNodeWithText("Terminal name").performTextInput("Payments emulator")
        compose.onNodeWithText("Pair terminal").performScrollTo().performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("ACTIVE").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Amount in AUD").performScrollTo().performTextInput("25.00")
        compose.waitUntil(20000) {
            compose
                .onAllNodes(hasText("Charge A$25.00") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Charge A$25.00").performScrollTo().assertIsEnabled().performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("Check payment status").fetchSemanticsNodes().isNotEmpty()
        }
        val auth = MobileAuthRepository(api, RefreshTokenStore(context), "MERCHANT")
        val user = checkNotNull(runBlocking { auth.restore() })
        val repo = SaleRepository(user, auth, PosLocalStore(context))
        val sale = checkNotNull(runBlocking { repo.recover() })
        val challenge = checkNotNull(sale.challenge)
        val customer = runBlocking {
            api.login("customer@example.test", "instrumentation-only-passphrase")
        }
        val alias = "tapflow-payment-test-${UUID.randomUUID()}"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val pair =
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                    .apply {
                        initialize(
                            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                                .setDigests(KeyProperties.DIGEST_SHA256)
                                .build()
                        )
                    }
                    .generateKeyPair()
            assertNull(pair.private.encoded)
            val card = runBlocking {
                api.resource(
                    "/cards",
                    customer.access,
                    JSONObject()
                        .put("label", "Emulator payment card")
                        .put("algorithm", "P256_SHA256_ECDSA")
                        .put(
                            "publicKeySpkiBase64",
                            Base64.getEncoder().encodeToString(pair.public.encoded),
                        ),
                    UUID.randomUUID().toString(),
                )
            }.getString("cardId")
            val id = UUID.fromString(card)
            val signature =
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(pair.private)
                    update(ChallengeCodec.canonical(challenge, id))
                    sign()
                }
            try {
                runBlocking { repo.authorise(ApduCodec.Proof(id, signature)) }
                fail("Fixture must suppress the committed outcome and first status read")
            } catch (error: AuthFailure) {
                assertEquals(503, error.status)
            }
            assertEquals(
                7500L,
                runBlocking { api.resource("/cards/$card/wallet", customer.access) }
                    .getLong("balanceMinor"),
            )
            compose.onNodeWithText("Check payment status").performScrollTo().performClick()
            compose.waitUntil(20000) {
                compose.onAllNodesWithText("RECOVERING").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("New sale").assertDoesNotExist()
            compose.waitUntil(20000) {
                compose
                    .onAllNodes(hasText("Check payment status") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Check payment status").performScrollTo().performClick()
            compose.waitUntil(20000) {
                compose.onAllNodesWithText("APPROVED").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("APPROVED").performScrollTo().assertIsDisplayed()
            assertEquals(sale.record.createKey, runBlocking { repo.saved() }?.record?.createKey)
            val entries = runBlocking {
                api.resource("/cards/$card/activity", customer.access)
            }.getJSONArray("items")
            assertEquals(2, entries.length())
            compose.onNodeWithText("DEMO / NO REAL MONEY").assertIsDisplayed()
            ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(
                        "screencap -p /data/local/tmp/tapflow-phase5-approved.png"
                    )
                )
                .use { it.readBytes() }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(20000) {
                compose.onAllNodesWithText("APPROVED").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(20000) {
                compose
                    .onAllNodes(hasText("New sale") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("New sale").performScrollTo().performClick()
            compose.onNodeWithText("Amount in AUD").performScrollTo().performTextInput("1.00")
            compose.onNodeWithText("Charge A$1.00").performScrollTo().performClick()
            compose.waitUntil(20000) {
                compose.onAllNodesWithText("Cancel payment").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Cancel payment").performScrollTo().performClick()
            compose.waitUntil(20000) {
                compose.onAllNodesWithText("CANCELLED").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(
                7500L,
                runBlocking { api.resource("/cards/$card/wallet", customer.access) }
                    .getLong("balanceMinor"),
            )
            runBlocking {
                api.logout(customer.access, customer.refresh)
                api.logout(owner.access, owner.refresh)
            }
            compose.onNodeWithText("Sign out").performScrollTo().performClick()
            compose.waitUntil(20000) {
                compose.onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            keys.deleteEntry(alias)
        }
    }
}
