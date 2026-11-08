package com.tapflow.protocol

object ReaderExchange {
    fun read(challenge: Challenge, transceive: (ByteArray) -> ByteArray): ApduCodec.Proof {
        val selected = transceive(ApduCodec.selectCommand())
        require(selected.contentEquals(byteArrayOf(1, 0x90.toByte(), 0))) {
            "The card did not accept TapFlow protocol v1. Unlock it and enable demo tapping."
        }
        return ApduCodec.decodeProof(transceive(ApduCodec.signCommand(challenge)))
    }
}
