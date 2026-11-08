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
        val data =
            try {
                val parts = saved.split('.')
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
                    "Saved pairing setup is unreadable. Clear pending pairing to use a new owner code."
                )
            }
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
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val payload =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                "." +
                Base64.encodeToString(
                    cipher.doFinal(data.toByteArray(Charsets.UTF_8)),
                    Base64.NO_WRAP,
                )
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
