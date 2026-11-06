package com.tapflow.pos

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class BootstrapScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun identifiesTerminalAppAndDemoBoundary() {
        compose.onNodeWithText("DEMO / NO REAL MONEY").assertIsDisplayed()
        compose.onNodeWithText("Ready for setup.").assertIsDisplayed()
        compose.onNodeWithText("Terminal not paired").assertIsDisplayed()
    }
}
