package com.tapflow.api.terminals

import kotlinx.serialization.Serializable

@Serializable data class PairingCodeRequest(val labelHint: String? = null)

@Serializable
data class PairTerminalRequest(
    val pairingCode: String,
    val installationId: String,
    val label: String,
) {
    override fun toString() = "PairTerminalRequest([redacted])"
}

@Serializable
data class Terminal(
    val terminalId: String,
    val merchantId: String,
    val label: String,
    val status: String,
    val pairedAt: String,
    val lastSeenAt: String? = null,
    val demo: Boolean = true,
)

@Serializable
data class TerminalPage(
    val items: List<Terminal>,
    val nextCursor: String? = null,
    val demo: Boolean = true,
)
