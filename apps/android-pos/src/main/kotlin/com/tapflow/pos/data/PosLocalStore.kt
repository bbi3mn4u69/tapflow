package com.tapflow.pos.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class PendingPairing(val owner: String, val code: String, val label: String, val key: String) {
    override fun toString() = "PendingPairing([redacted])"
}

class PosLocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("tapflow_pos", Context.MODE_PRIVATE)

    companion object {
        private val saleLock = Any()
    }

    fun prepareSale(owner: String, merchant: String, terminal: String, amount: Long): SavedSale =
        synchronized(saleLock) {
            require(amount in 1..1_000_000)
            sale(owner)?.let {
                return@synchronized it
            }
            check(!prefs.contains("sale")) {
                "Another account has an unresolved payment on this device."
            }
            SavedSale(
                    owner,
                    merchant,
                    terminal,
                    amount,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                )
                .also { writeSale(it) }
        }

    fun sale(owner: String): SavedSale? =
        synchronized(saleLock) {
            val payload = prefs.getString("sale", null) ?: return@synchronized null
            val data = unseal(payload)
            if (data.getString("owner") != owner) return@synchronized null
            SavedSale(
                owner,
                data.getString("merchant"),
                data.getString("terminal"),
                data.getLong("amount"),
                data.getString("createKey"),
                data.getString("authoriseKey"),
                data.optJSONObject("intent"),
                data.optJSONObject("proof"),
                data.getBoolean("cancelRequested"),
                if (data.isNull("finalStatus")) null else data.getString("finalStatus"),
                data.optJSONObject("receipt"),
            )
        }

    fun saveSale(record: SavedSale) =
        synchronized(saleLock) {
            check(sale(record.owner)?.createKey == record.createKey) {
                "Payment setup changed. Recover the current payment."
            }
            writeSale(record)
        }

    private fun writeSale(record: SavedSale) {
        val data =
            JSONObject()
                .put("owner", record.owner)
                .put("merchant", record.merchant)
                .put("terminal", record.terminal)
                .put("amount", record.amount)
                .put("createKey", record.createKey)
                .put("authoriseKey", record.authoriseKey)
                .put("intent", record.intent ?: JSONObject.NULL)
                .put("proof", record.proof ?: JSONObject.NULL)
                .put("cancelRequested", record.cancelRequested)
                .put("finalStatus", record.finalStatus ?: JSONObject.NULL)
                .put("receipt", record.receipt ?: JSONObject.NULL)
        if (!prefs.edit().putString("sale", seal(data)).commit())
            throw IOException("Could not save payment recovery data")
    }

    fun acknowledgeSale(owner: String) =
        synchronized(saleLock) {
            val record = checkNotNull(sale(owner))
            check(record.finalStatus in setOf("APPROVED", "DECLINED", "CANCELLED", "EXPIRED")) {
                "Check payment status before starting another sale."
            }
            if (!prefs.edit().remove("sale").commit())
                throw IOException("Could not clear the resolved payment")
        }

    private fun seal(data: JSONObject): String {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            "." +
            Base64.encodeToString(
                cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP,
            )
    }

    private fun unseal(payload: String): JSONObject =
        try {
            val parts = payload.split('.')
            check(parts.size == 2)
            val cipher =
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        key(),
                        GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
                    )
                }
            JSONObject(
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
            )
        } catch (_: Exception) {
            throw IOException(
                "Saved setup is unreadable. Do not start another sale until its status has been checked."
            )
        }

    private val alias = "tapflow_pos_pending_aes_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                alias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build()
                    )
                }
                .generateKey()
    }

    @Synchronized
    fun installation(): String =
        prefs.getString("installation", null)
            ?: UUID.randomUUID().toString().also {
                if (!prefs.edit().putString("installation", it).commit())
                    throw IOException("Could not save terminal installation")
            }

    @Synchronized
    fun pending(owner: String): PendingPairing? {
        val saved = prefs.getString("pending", null) ?: return null
        val data = unseal(saved)
        if (data.getString("owner") != owner) return null
        return PendingPairing(
            owner,
            data.getString("code"),
            data.getString("label"),
            data.getString("key"),
        )
    }

    @Synchronized
    fun prepare(owner: String, code: String, label: String): PendingPairing {
        pending(owner)?.let {
            return it
        }
        check(!prefs.contains("pending")) {
            "Another account has unfinished pairing on this device. Sign in to that account first."
        }
        val pending = PendingPairing(owner, code, label, UUID.randomUUID().toString())
        val data =
            JSONObject()
                .put("owner", owner)
                .put("code", code)
                .put("label", label)
                .put("key", pending.key)
                .toString()
        val payload = seal(JSONObject(data))
        if (!prefs.edit().putString("pending", payload).commit())
            throw IOException("Could not save pending pairing")
        return pending
    }

    @Synchronized
    fun clearPending() {
        if (!prefs.edit().remove("pending").commit())
            throw IOException("Could not clear pending pairing")
    }

    fun binding(): String? = prefs.getString("binding", null)

    fun bind(terminal: JSONObject) {
        if (!prefs.edit().putString("binding", terminal.toString()).remove("pending").commit())
            throw IOException("Could not save paired terminal")
    }
}

object AmountInput {
    fun parse(value: String): Long? {
        if (!value.matches(Regex("[0-9]{1,5}(\\.[0-9]{1,2})?"))) return null
        val parts = value.split('.')
        val minor = parts[0].toLong() * 100 + (parts.getOrNull(1)?.padEnd(2, '0')?.toLong() ?: 0)
        return minor.takeIf { it in 1..1_000_000 }
    }
}
