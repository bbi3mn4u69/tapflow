package com.tapflow.card

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tapflow.androidauth.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LiveSignInTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun signsInRestoresEncryptedRefreshAndSignsOutAgainstAnIsolatedApi() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tapflowLiveAuth") == "true")
        compose.waitUntil(10000) { compose.onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Email").performTextInput("customer@example.test")
        compose.onNodeWithText("Password").performTextInput("instrumentation-only-passphrase")
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("customer@example.test").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("customer@example.test").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val restored = MobileAuthRepository(MobileAuthApi(BuildConfig.API_BASE_URL, true), RefreshTokenStore(context), "CUSTOMER")
            assertEquals("customer@example.test", restored.restore()?.email)
        }
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.waitUntil(20000) { compose.onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty() }
        assertNull(RefreshTokenStore(context).read())
    }
    @Test fun refusesAMerchantAccountInTheCustomerAppAndClearsItsSession() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tapflowLiveAuth") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RefreshTokenStore(context)
        runBlocking {
            val repository = MobileAuthRepository(MobileAuthApi(BuildConfig.API_BASE_URL, true), store, "CUSTOMER")
            try {
                repository.login("operator@example.test", "instrumentation-only-passphrase")
                fail("Merchant login must not open the customer app")
            } catch (failure: AuthFailure) { assertEquals(403, failure.status) }
            assertNull(store.read())
        }
    }
}
