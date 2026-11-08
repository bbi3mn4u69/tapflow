package com.tapflow.card.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class CardKeyManager {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun create(alias: String) {
        require(alias.length in 1..160 && alias.startsWith("tapflow")) { "Invalid card key alias" }
        if (exists(alias)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build()
                )
            }
            .generateKeyPair()
    }

    fun publicKey(alias: String): ByteArray =
        checkNotNull(store().getCertificate(alias)).publicKey.encoded

    fun exists(alias: String) = store().getCertificate(alias)?.publicKey is ECPublicKey

    fun sign(alias: String, bytes: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(store().getKey(alias, null) as PrivateKey)
            update(bytes)
            sign()
        }
}
