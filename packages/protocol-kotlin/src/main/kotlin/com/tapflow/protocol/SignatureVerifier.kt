package com.tapflow.protocol

import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

object SignatureVerifier {
    private val order =
        BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private val prefix =
        byteArrayOf(
            0x30,
            0x59,
            0x30,
            0x13,
            0x06,
            0x07,
            0x2a,
            0x86.toByte(),
            0x48,
            0xce.toByte(),
            0x3d,
            0x02,
            0x01,
            0x06,
            0x08,
            0x2a,
            0x86.toByte(),
            0x48,
            0xce.toByte(),
            0x3d,
            0x03,
            0x01,
            0x07,
            0x03,
            0x42,
            0x00,
            0x04,
        )

    fun publicKey(spki: ByteArray): ECPublicKey {
        require(spki.size == 91 && spki.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            "A canonical uncompressed P-256 SPKI key is required"
        }
        val key =
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey
        val parameters = key.params
        require(
            parameters.order == order &&
                parameters.cofactor == 1 &&
                parameters.curve.field.fieldSize == 256
        ) {
            "Invalid curve"
        }
        val p = (parameters.curve.field as ECFieldFp).p
        val x = key.w.affineX
        val y = key.w.affineY
        require(x.signum() >= 0 && x < p && y.signum() >= 0 && y < p) { "Invalid point" }
        require(
            y.modPow(BigInteger.valueOf(2), p) ==
                (x.modPow(BigInteger.valueOf(3), p) + parameters.curve.a * x + parameters.curve.b)
                    .mod(p)
        ) {
            "Point is not on the curve"
        }
        require(key.encoded.contentEquals(spki)) { "Invalid key encoding" }
        return key
    }

    fun isDer(signature: ByteArray): Boolean {
        if (
            signature.size !in 8..72 ||
                signature[0].toInt() != 0x30 ||
                signature[1].toInt() != signature.size - 2
        )
            return false
        var offset = 2
        repeat(2) {
            if (offset + 2 > signature.size || signature[offset++].toInt() != 2) return false
            val length = signature[offset++].toInt()
            if (length !in 1..33 || offset + length > signature.size) return false
            if (signature[offset].toInt() and 0x80 != 0) return false
            if (
                length > 1 &&
                    signature[offset].toInt() == 0 &&
                    signature[offset + 1].toInt() and 0x80 == 0
            )
                return false
            val integer = BigInteger(1, signature.copyOfRange(offset, offset + length))
            if (integer.signum() == 0 || integer >= order) return false
            offset += length
        }
        return offset == signature.size
    }

    fun verify(challenge: Challenge, cardId: UUID, spki: ByteArray, signature: ByteArray): Boolean {
        if (!isDer(signature)) return false
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey(spki))
                update(ChallengeCodec.canonical(challenge, cardId))
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
    }
}
