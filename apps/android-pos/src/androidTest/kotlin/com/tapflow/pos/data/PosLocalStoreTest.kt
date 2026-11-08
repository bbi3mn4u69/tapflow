package com.tapflow.pos.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PosLocalStoreTest {
    @Test
    fun pendingPairingIsEncryptedStableAndAccessibleOnlyToItsAccount() {
        val suffix = UUID.randomUUID().toString()
        val context =
            object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("$name-$suffix", mode)
            }
        val store = PosLocalStore(context)
        val owner = UUID.randomUUID().toString()
        val first = store.prepare(owner, "ABCDEF1234567", "Test counter")
        val reloaded = PosLocalStore(context)
        assertEquals(first.key, reloaded.prepare(owner, "ZZZZZZZZZZZZZ", "Other").key)
        assertEquals("ABCDEF1234567", reloaded.pending(owner)?.code)
        assertNull(reloaded.pending(UUID.randomUUID().toString()))
        assertFalse(
            context
                .getSharedPreferences("tapflow_pos", Context.MODE_PRIVATE)
                .all
                .values
                .joinToString()
                .contains("ABCDEF1234567")
        )
        assertEquals(store.installation(), reloaded.installation())
        reloaded.clearPending()
        assertNull(store.pending(owner))
    }

    @Test
    fun amountEntryUsesExactMinorUnitsAndRejectsInvalidOrOutOfRangeValues() {
        assertEquals(2500L, AmountInput.parse("25.00"))
        assertEquals(1L, AmountInput.parse("0.01"))
        assertEquals(1000000L, AmountInput.parse("10000.00"))
        for (raw in listOf("0", "10000.01", "1.999", "NaN", "-1", "1e9", "", ".5")) assertNull(
            raw,
            AmountInput.parse(raw),
        )
    }
}
