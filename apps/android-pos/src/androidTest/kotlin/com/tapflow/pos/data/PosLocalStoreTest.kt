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

    @Test
    fun saleJournalIsEncryptedAccountScopedAndRejectsStaleWrites() {
        val suffix = UUID.randomUUID().toString()
        val context =
            object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("$name-$suffix", mode)
            }
        val store = PosLocalStore(context)
        val owner = UUID.randomUUID().toString()
        val merchant = UUID.randomUUID().toString()
        val terminal = UUID.randomUUID().toString()
        val first = store.prepareSale(owner, merchant, terminal, 2500)
        val proof = org.json.JSONObject().put("signatureDerBase64", "synthetic-sensitive-proof")
        store.saveSale(first.copy(proof = proof))
        val restored = PosLocalStore(context).sale(owner)!!
        assertEquals(first.createKey, restored.createKey)
        assertEquals(first.authoriseKey, restored.authoriseKey)
        assertEquals("synthetic-sensitive-proof", restored.proof?.getString("signatureDerBase64"))
        assertNull(store.sale(UUID.randomUUID().toString()))
        assertFalse(
            context
                .getSharedPreferences("tapflow_pos", Context.MODE_PRIVATE)
                .all
                .values
                .joinToString()
                .contains("synthetic-sensitive-proof")
        )
        try {
            store.acknowledgeSale(owner)
            fail("Unresolved payment must not be discarded")
        } catch (_: IllegalStateException) {}
        store.saveSale(restored.copy(finalStatus = "CANCELLED", proof = null))
        store.acknowledgeSale(owner)
        val next = store.prepareSale(owner, merchant, terminal, 100)
        try {
            store.saveSale(restored)
            fail("Old workers must not overwrite a new payment")
        } catch (_: IllegalStateException) {}
        assertEquals(next.createKey, store.sale(owner)?.createKey)
        store.saveSale(next.copy(finalStatus = "CANCELLED"))
        store.acknowledgeSale(owner)
    }
}
