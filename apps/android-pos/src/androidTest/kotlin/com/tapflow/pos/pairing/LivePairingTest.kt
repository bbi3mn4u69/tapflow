package com.tapflow.pos.pairing

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tapflow.androidauth.MobileAuthApi
import com.tapflow.androidauth.MobileAuthRepository
import com.tapflow.androidauth.RefreshTokenStore
import com.tapflow.pos.BuildConfig
import com.tapflow.pos.MainActivity
import com.tapflow.pos.data.PosLocalStore
import com.tapflow.pos.data.PosRepository
import com.tapflow.pos.ui.PosViewModel
import com.tapflow.protocol.ReaderSession
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LivePairingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun operatorPairsRestoresAndObservesOwnerDisablingOnTheEmulator() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tapflowLiveAuth") == "true")
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
        compose.onNodeWithText("Terminal name").performTextInput("Emulator counter")
        compose.onNodeWithText("Pair terminal").performScrollTo().performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("Emulator counter").fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("Emulator counter").fetchSemanticsNodes().isNotEmpty()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val restored =
            MobileAuthRepository(api, RefreshTokenStore(instrumentation.targetContext), "MERCHANT")
        val restoredUser = checkNotNull(runBlocking { restored.restore() })
        val readerModel =
            PosViewModel(
                PosRepository(restoredUser, restored, PosLocalStore(instrumentation.targetContext))
            )
        try {
            instrumentation.runOnMainSync {
                readerModel.startDiagnostic(2500)
                readerModel.cancelDiagnostic()
            }
            compose.waitUntil(5000) {
                !readerModel.state.value.busy &&
                    readerModel.state.value.phase == ReaderSession.Phase.IDLE
            }
        } finally {
            instrumentation.runOnMainSync { readerModel.clear() }
        }
        compose.onNodeWithText("Amount in AUD").performScrollTo().performTextInput("25.00")
        compose.onNodeWithText("Charge A$25.00").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Test NFC connection").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("DEMO / NO REAL MONEY").assertIsDisplayed()
        compose.onNodeWithText("Amount in AUD").performImeAction()
        compose.onNodeWithText("Emulator counter").performScrollTo()
        ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand("screencap -p /data/local/tmp/tapflow-phase4-pos.png")
            )
            .use { it.readBytes() }
        runBlocking {
            val list = api.resource("/merchants/$merchant/terminals?limit=100", owner.access)
            val id = list.getJSONArray("items").getJSONObject(0).getString("terminalId")
            api.resource("/merchants/$merchant/terminals/$id/disable", owner.access, JSONObject())
            api.logout(owner.access, owner.refresh)
        }
        compose.onNodeWithText("Refresh terminal").performScrollTo().performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("DISABLED").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
