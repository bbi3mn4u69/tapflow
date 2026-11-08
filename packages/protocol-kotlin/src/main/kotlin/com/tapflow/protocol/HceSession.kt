package com.tapflow.protocol

class HceSession {
    sealed interface Action {
        class Reply(val bytes: ByteArray) : Action

        class Sign(val generation: Long, val challenge: Challenge) : Action
    }

    private var generation = 0L
    private var selected = false
    private var signing = false

    @Synchronized
    fun deactivate() {
        generation++
        selected = false
        signing = false
    }

    @Synchronized
    fun handle(bytes: ByteArray, unlocked: Boolean): Action =
        when (val command = ApduCodec.parse(bytes)) {
            ApduCodec.Command.Select -> {
                deactivate()
                selected = unlocked
                Action.Reply(
                    if (unlocked) byteArrayOf(1) + ApduCodec.status(ApduCodec.OK)
                    else ApduCodec.status(ApduCodec.NOT_READY)
                )
            }
            is ApduCodec.Command.Rejected -> {
                deactivate()
                Action.Reply(ApduCodec.status(command.status))
            }
            is ApduCodec.Command.Sign -> {
                if (!selected || signing || !unlocked) {
                    deactivate()
                    Action.Reply(ApduCodec.status(ApduCodec.NOT_READY))
                } else {
                    signing = true
                    Action.Sign(generation, command.challenge)
                }
            }
        }

    @Synchronized
    fun complete(token: Long, response: ByteArray): ByteArray? {
        if (token != generation || !selected || !signing) return null
        selected = false
        signing = false
        return response.copyOf()
    }
}
