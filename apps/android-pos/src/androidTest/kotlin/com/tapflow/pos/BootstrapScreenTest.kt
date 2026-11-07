package com.tapflow.pos

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class BootstrapScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun identifiesAppAndOffersSecureSignIn() {
        compose.onNodeWithText("DEMO / NO REAL MONEY").assertIsDisplayed()
        compose.onNodeWithText("Merchant POS").assertIsDisplayed()
        compose.onNodeWithText("Email").assertIsDisplayed()
        compose.onNodeWithText("Password").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertIsDisplayed()
    }
}
