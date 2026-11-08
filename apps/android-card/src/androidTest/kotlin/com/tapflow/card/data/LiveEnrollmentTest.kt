package com.tapflow.card.data

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tapflow.card.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LiveEnrollmentTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun enrollsOneDeviceCardShowsFundedWalletAndDisablesIt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tapflowLiveAuth") == "true")
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Email").performTextInput("customer@example.test")
        compose.onNodeWithText("Password").performTextInput("instrumentation-only-passphrase")
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("Enroll demo card").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Enroll demo card").performScrollTo().performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("A$100.00").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("A$100.00").onFirst().performScrollTo().assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "screencap -p /data/local/tmp/tapflow-phase3-wallet.png"
                )
            )
            .use { it.readBytes() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("A$100.00").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Refresh wallet").performScrollTo().performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("A$100.00").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(20000) {
            compose
                .onAllNodes(hasText("Disable this card") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Disable this card").performScrollTo()
        compose.onNodeWithText("DEMO / NO REAL MONEY").assertIsDisplayed()
        compose.onNodeWithText("Disable this card").performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("BLOCKED").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
