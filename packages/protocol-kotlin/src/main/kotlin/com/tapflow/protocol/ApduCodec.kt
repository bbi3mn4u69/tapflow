package com.tapflow.protocol

import com.tapflow.protocol.ChallengeCodec.getUuid
import java.nio.ByteBuffer
import java.util.UUID

object ApduCodec {
    const val OK = 0x9000
    const val UNKNOWN_AID = 0x6a82
    const val MALFORMED = 0x6a80
    const val NOT_READY = 0x6985
    const val UNSUPPORTED = 0x6d00
    const val INTERNAL = 0x6f00
    private val aid = byteArrayOf(0xf0.toByte(), 0x54, 0x41, 0x50, 0x46, 0x4c, 0x4f, 0x57, 0x31)

    sealed interface Command {
        data object Select : Command

        class Sign(val challenge: Challenge) : Command

        class Rejected(val status: Int) : Command
    }

    class Proof(val cardId: UUID, signature: ByteArray) {
        private val bytes = signature.copyOf()
        val signature: ByteArray
            get() = bytes.copyOf()
    }

    fun status(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

    fun selectCommand(): ByteArray = byteArrayOf(0, 0xa4.toByte(), 4, 0, 9) + aid + byteArrayOf(0)

    fun signCommand(value: Challenge): ByteArray =
        byteArrayOf(0x80.toByte(), 0x20, 0, 0, 68) + ChallengeCodec.encode(value) + byteArrayOf(0)

    fun parse(bytes: ByteArray): Command {
        if (bytes.size !in 4..261) return Command.Rejected(MALFORMED)
        if (bytes[0].toInt() == 0 && bytes[1].toInt() and 0xff == 0xa4) {
            if (
                bytes.size != 15 ||
                    bytes[2].toInt() != 4 ||
                    bytes[3].toInt() != 0 ||
                    bytes[4].toInt() != 9 ||
                    bytes.last().toInt() != 0
            )
                return Command.Rejected(MALFORMED)
            return if (bytes.copyOfRange(5, 14).contentEquals(aid)) Command.Select
            else Command.Rejected(UNKNOWN_AID)
        }
        if (bytes[0].toInt() and 0xff != 0x80 || bytes[1].toInt() != 0x20)
            return Command.Rejected(UNSUPPORTED)
        if (
            bytes.size != 74 ||
                bytes[2].toInt() != 0 ||
                bytes[3].toInt() != 0 ||
                bytes[4].toInt() != 68 ||
                bytes.last().toInt() != 0
        )
            return Command.Rejected(MALFORMED)
        return try {
            Command.Sign(ChallengeCodec.decode(bytes.copyOfRange(5, 73)))
        } catch (_: IllegalArgumentException) {
            Command.Rejected(MALFORMED)
        }
    }

    fun selectResponse(command: ByteArray): ByteArray =
        when (val parsed = parse(command)) {
            Command.Select -> byteArrayOf(1) + status(OK)
            is Command.Rejected -> status(parsed.status)
            else -> status(NOT_READY)
        }

    fun signResponse(cardId: UUID, signature: ByteArray): ByteArray {
        require(SignatureVerifier.isDer(signature)) { "Invalid signature" }
        return byteArrayOf(1) +
            ChallengeCodec.uuidBytes(cardId) +
            byteArrayOf(signature.size.toByte()) +
            signature +
            status(OK)
    }

    fun decodeProof(bytes: ByteArray): Proof {
        require(
            bytes.size in 28..92 &&
                bytes[0].toInt() == 1 &&
                bytes.takeLast(2).toByteArray().contentEquals(status(OK))
        ) {
            "Invalid proof response"
        }
        val length = bytes[17].toInt() and 0xff
        require(length in 8..72 && bytes.size == length + 20) { "Invalid proof length" }
        val signature = bytes.copyOfRange(18, 18 + length)
        require(SignatureVerifier.isDer(signature)) { "Invalid signature encoding" }
        return Proof(ByteBuffer.wrap(bytes, 1, 16).getUuid(), signature)
    }
}
