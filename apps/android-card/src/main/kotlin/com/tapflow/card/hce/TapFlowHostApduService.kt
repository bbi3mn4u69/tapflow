package com.tapflow.card.hce

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

class TapFlowHostApduService : HostApduService() {
    private lateinit var engine: CardApduEngine

    override fun onCreate() {
        super.onCreate()
        engine = CardApduEngine(applicationContext, { sendResponseApdu(it) })
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray? =
        engine.command(commandApdu ?: byteArrayOf())

    override fun onDeactivated(reason: Int) {
        engine.deactivate()
    }

    override fun onDestroy() {
        engine.close()
        super.onDestroy()
    }
}
