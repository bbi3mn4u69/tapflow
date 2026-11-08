package com.tapflow.pos.data

import com.tapflow.androidauth.MobileAuthRepository
import com.tapflow.androidauth.MobileUser
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PairedTerminal(
    val id: String,
    val merchantId: String,
    val label: String,
    val status: String,
)

data class PosSnapshot(val terminal: PairedTerminal?, val pending: PendingPairing?)

class PosRepository(
    private val user: MobileUser,
    private val auth: MobileAuthRepository,
    private val local: PosLocalStore,
) {
    private val mutex = Mutex()

    suspend fun refresh(): PosSnapshot = mutex.withLock { load() }

    suspend fun pair(code: String, label: String): PosSnapshot = mutex.withLock {
        val normalized =
            code.filterNot { it.isWhitespace() || it == '-' }.uppercase(java.util.Locale.ROOT)
        val pending =
            withContext(Dispatchers.IO) {
                local.pending(user.id)
                    ?: run {
                        require(
                            normalized.matches(Regex("[0-9A-HJKMNP-TV-Z]{13}")) &&
                                label.trim().length in 1..80
                        ) {
                            "Enter a 13-character owner code and a terminal name of 1–80 characters."
                        }
                        local.prepare(user.id, normalized, label.trim())
                    }
            }
        val installation = withContext(Dispatchers.IO) { local.installation() }
        val response =
            auth.request(
                "/terminals/pair",
                JSONObject()
                    .put("pairingCode", pending.code)
                    .put("installationId", installation)
                    .put("label", pending.label),
                pending.key,
            )
        val terminal = parse(response)
        check(user.memberships.any { it.merchantId == terminal.merchantId }) {
            "This terminal belongs to another workspace."
        }
        withContext(Dispatchers.IO) { local.bind(response) }
        load()
    }

    suspend fun clearPending(): PosSnapshot = mutex.withLock {
        withContext(Dispatchers.IO) { local.clearPending() }
        load()
    }

    private suspend fun load(): PosSnapshot {
        val saved = withContext(Dispatchers.IO) { local.binding() }
        val pending = withContext(Dispatchers.IO) { local.pending(user.id) }
        if (saved == null) return PosSnapshot(null, pending)
        val binding = parse(JSONObject(saved))
        check(user.memberships.any { it.merchantId == binding.merchantId }) {
            "This device is paired to another workspace. Use its merchant account."
        }
        val page = auth.request("/merchants/${binding.merchantId}/terminals?limit=100")
        check(page.getBoolean("demo"))
        val items = page.getJSONArray("items")
        val terminal =
            (0 until items.length())
                .map { parse(items.getJSONObject(it)) }
                .firstOrNull { it.id == binding.id }
        check(terminal != null) {
            "This terminal is unavailable. Ask your merchant owner for help."
        }
        return PosSnapshot(terminal, pending)
    }

    suspend fun pending(): PendingPairing? = withContext(Dispatchers.IO) { local.pending(user.id) }

    private fun parse(value: JSONObject): PairedTerminal {
        check(value.getBoolean("demo"))
        val status = value.getString("status")
        check(status in setOf("ACTIVE", "DISABLED"))
        return PairedTerminal(
            UUID.fromString(value.getString("terminalId")).toString(),
            UUID.fromString(value.getString("merchantId")).toString(),
            value.getString("label"),
            status,
        )
    }
}
