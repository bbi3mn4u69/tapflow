package com.tapflow.card

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class BootstrapScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun identifiesCustomerAppAndDemoBoundary() {
        compose.onNodeWithText("DEMO / NO REAL MONEY").assertIsDisplayed()
        compose.onNodeWithText("Your demo card.").assertIsDisplayed()
        compose.onNodeWithText("Not enrolled").assertIsDisplayed()
    }
}
