package com.tapflow.androidauth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class RefreshTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("tapflow_auth", Context.MODE_PRIVATE)
    private val alias = "tapflow_refresh_aes_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey)
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
    fun save(value: String, userId: String? = null) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                "." +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        if (!preferences.edit().putString("refresh", payload).putString("userId", userId).commit())
            throw IOException("Could not save the encrypted session")
    }

    @Synchronized
    fun read(): String? {
        val payload = preferences.getString("refresh", null) ?: return null
        return try {
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
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            clear()
            null
        }
    }

    @Synchronized
    fun clear() {
        if (!preferences.edit().remove("refresh").remove("userId").commit())
            throw IOException("Could not clear the encrypted session")
    }
}
