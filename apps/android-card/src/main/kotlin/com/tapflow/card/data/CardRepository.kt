package com.tapflow.card.data

import com.tapflow.androidauth.MobileAuthRepository
import com.tapflow.card.crypto.CardKeyManager
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DeviceCard(val id: String, val label: String, val status: String)

class ActivityEntry(
    val id: String,
    val type: String,
    val amountMinor: Long,
    val balanceAfterMinor: Long,
    val merchantName: String?,
    val createdAt: String,
)

data class CardSnapshot(
    val cards: List<DeviceCard>,
    val binding: CardBinding?,
    val balance: Long?,
    val activity: List<ActivityEntry>,
    val nextCursor: String?,
    val keyPresent: Boolean,
    val tapping: Boolean,
    val lastTap: String?,
)

class CardRepository(
    private val owner: String,
    private val auth: MobileAuthRepository,
    private val local: CardLocalStore,
    private val keys: CardKeyManager = CardKeyManager(),
) {
    private val mutex = Mutex()

    suspend fun enroll(label: String): CardSnapshot = mutex.withLock {
        require(label.trim().length in 1..80) { "Enter a card label of 1–80 characters." }
        val binding =
            withContext(Dispatchers.IO) {
                val existing = local.read(owner)
                val pending =
                    existing
                        ?: local.pending(owner, label.trim()).also {
                            keys.create(it.alias)
                            local.save(it)
                        }
                check(keys.exists(pending.alias)) {
                    "Device key is missing. Disable the lost card before creating a replacement."
                }
                pending
            }
        val public = withContext(Dispatchers.IO) { keys.publicKey(binding.alias) }
        val request =
            JSONObject()
                .put("label", binding.label)
                .put("algorithm", "P256_SHA256_ECDSA")
                .put("publicKeySpkiBase64", Base64.getEncoder().encodeToString(public))
        val result = card(auth.request("/cards", request, binding.enrollmentId))
        withContext(Dispatchers.IO) { local.confirmed(binding, result.id, result.status) }
        load()
    }

    fun localSnapshot(previous: CardSnapshot?): CardSnapshot {
        val binding = local.read(owner)
        return CardSnapshot(
            previous?.cards ?: emptyList(),
            binding,
            previous?.balance,
            previous?.activity ?: emptyList(),
            previous?.nextCursor,
            binding?.let { keys.exists(it.alias) } == true,
            local.enabled(owner),
            local.lastTap(owner),
        )
    }

    suspend fun refresh(): CardSnapshot = mutex.withLock { load() }

    private suspend fun load(): CardSnapshot {
        val result = auth.request("/cards")
        check(result.getBoolean("demo"))
        val items = result.getJSONArray("items")
        val cards = (0 until items.length()).map { card(items.getJSONObject(it)) }
        var binding = withContext(Dispatchers.IO) { local.read(owner) }
        val active = cards.firstOrNull { it.id == binding?.cardId }
        if (binding != null && active != null)
            binding =
                withContext(Dispatchers.IO) { local.confirmed(binding!!, active.id, active.status) }
        var balance: Long? = null
        var activity = emptyList<ActivityEntry>()
        var cursor: String? = null
        if (active != null) {
            val wallet = auth.request("/cards/${active.id}/wallet")
            check(wallet.getBoolean("demo") && wallet.getString("currency") == "AUD")
            balance = wallet.getLong("balanceMinor")
            check(balance >= 0)
            val page = activity(active.id, null)
            activity = page.first
            cursor = page.second
        }
        return withContext(Dispatchers.IO) {
            CardSnapshot(
                cards,
                binding,
                balance,
                activity,
                cursor,
                binding?.let { keys.exists(it.alias) } == true,
                local.enabled(owner),
                local.lastTap(owner),
            )
        }
    }

    suspend fun more(card: String, cursor: String): Pair<List<ActivityEntry>, String?> =
        mutex.withLock {
            activity(card, cursor)
        }

    private suspend fun activity(
        card: String,
        cursor: String?,
    ): Pair<List<ActivityEntry>, String?> {
        val path = "/cards/$card/activity?limit=25" + (cursor?.let { "&cursor=$it" } ?: "")
        val page = auth.request(path)
        check(page.getBoolean("demo"))
        val items = page.getJSONArray("items")
        return (0 until items.length()).map {
            val entry = items.getJSONObject(it)
            ActivityEntry(
                UUID.fromString(entry.getString("id")).toString(),
                entry.getString("entryType"),
                entry.getLong("amountMinor"),
                entry.getLong("balanceAfterMinor"),
                if (entry.isNull("merchantName")) null else entry.getString("merchantName"),
                entry.getString("createdAt"),
            )
        } to if (page.isNull("nextCursor")) null else page.getString("nextCursor")
    }

    suspend fun disable(id: String): CardSnapshot = mutex.withLock {
        withContext(Dispatchers.IO) { local.pause() }
        val response = card(auth.request("/cards/${UUID.fromString(id)}/disable", JSONObject()))
        withContext(Dispatchers.IO) {
            local
                .read(owner)
                ?.takeIf { it.cardId == response.id }
                ?.let { local.confirmed(it, response.id, response.status) }
        }
        load()
    }

    suspend fun enable(): CardSnapshot = mutex.withLock {
        val snapshot = load()
        val binding = checkNotNull(snapshot.binding)
        check(binding.status == "ACTIVE" && snapshot.keyPresent) {
            "An active card and its device key are required."
        }
        withContext(Dispatchers.IO) { local.enable(owner) }
        snapshot.copy(tapping = true)
    }

    suspend fun pause() = mutex.withLock { withContext(Dispatchers.IO) { local.pause() } }

    suspend fun reset(): CardSnapshot = mutex.withLock {
        val snapshot = load()
        val binding = snapshot.binding
        check(
            binding == null ||
                (binding.cardId != null &&
                    snapshot.cards.none { it.id == binding.cardId && it.status == "ACTIVE" }) ||
                (binding.cardId == null && snapshot.cards.none { it.status == "ACTIVE" })
        ) {
            "Disable the old card before replacing this device setup."
        }
        withContext(Dispatchers.IO) { local.reset(owner) }
        load()
    }

    private fun card(value: JSONObject): DeviceCard {
        check(value.getBoolean("demo"))
        val status = value.getString("status")
        check(status in setOf("ACTIVE", "BLOCKED"))
        return DeviceCard(
            UUID.fromString(value.getString("cardId")).toString(),
            value.getString("label"),
            status,
        )
    }
}
