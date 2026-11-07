package com.tapflow.card

import androidx.test.platform.app.InstrumentationRegistry
import com.tapflow.androidauth.RefreshTokenStore
import org.junit.Assert.*
import org.junit.Test

class RefreshTokenStoreTest {
    @Test fun refreshTokenSurvivesAStoreReopenWithoutPlaintextAndTamperingSignsOut() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secret = "instrumentation-only-refresh-token-256-bits-test"
        val store = RefreshTokenStore(context)
        store.clear()
        try {
            store.save(secret)
            assertEquals(secret, RefreshTokenStore(context).read())
            val prefs = context.getSharedPreferences("tapflow_auth", 0)
            assertFalse(prefs.all.values.any { it.toString().contains(secret) })
            prefs.edit().putString("refresh", "corrupted-data").commit()
            assertNull(RefreshTokenStore(context).read())
            store.save(secret)
            store.clear()
            assertNull(RefreshTokenStore(context).read())
        } finally { store.clear() }
    }
}
