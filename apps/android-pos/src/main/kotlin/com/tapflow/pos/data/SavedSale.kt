package com.tapflow.pos.data

import org.json.JSONObject

data class SavedSale(
    val owner: String,
    val merchant: String,
    val terminal: String,
    val amount: Long,
    val createKey: String,
    val authoriseKey: String,
    val intent: JSONObject? = null,
    val proof: JSONObject? = null,
    val cancelRequested: Boolean = false,
    val finalStatus: String? = null,
    val receipt: JSONObject? = null,
) {
    override fun toString() = "SavedSale([redacted])"
}
