package com.tapflow.card.data

import android.content.Context
import java.io.IOException
import java.util.UUID
import org.json.JSONObject

class CardBinding(
    val owner: String,
    val alias: String,
    val enrollmentId: String,
    val label: String,
    val cardId: String? = null,
    val status: String = "PENDING",
)

class CardLocalStore(context: Context) {
    private val preferences = context.getSharedPreferences("tapflow_cards", Context.MODE_PRIVATE)
    private val auth = context.getSharedPreferences("tapflow_auth", Context.MODE_PRIVATE)

    fun read(owner: String): CardBinding? {
        val data = preferences.getString("binding:$owner", null) ?: return null
        return try {
            val json = JSONObject(data)
            CardBinding(
                owner,
                json.getString("alias"),
                json.getString("enrollmentId"),
                json.getString("label"),
                if (json.isNull("cardId")) null else json.getString("cardId"),
                json.getString("status"),
            )
        } catch (_: Exception) {
            pause()
            null
        }
    }

    fun pending(owner: String, label: String): CardBinding {
        read(owner)?.let {
            return it
        }
        val enrollment = UUID.randomUUID().toString()
        return CardBinding(owner, "tapflow_card_${owner}_$enrollment", enrollment, label)
    }

    fun save(binding: CardBinding) {
        val data =
            JSONObject()
                .put("alias", binding.alias)
                .put("enrollmentId", binding.enrollmentId)
                .put("label", binding.label)
                .put("cardId", binding.cardId ?: JSONObject.NULL)
                .put("status", binding.status)
        if (!preferences.edit().putString("binding:${binding.owner}", data.toString()).commit())
            throw IOException("Could not save card setup")
    }

    fun confirmed(binding: CardBinding, id: String, status: String): CardBinding =
        CardBinding(binding.owner, binding.alias, binding.enrollmentId, binding.label, id, status)
            .also(::save)

    fun pause() {
        if (!preferences.edit().remove("enabledOwner").remove("enabledSession").commit())
            throw IOException("Could not pause demo tapping")
    }

    fun enable(owner: String) {
        check(auth.getString("userId", null) == owner) { "Sign in before enabling demo tapping." }
        val session = checkNotNull(auth.getString("refresh", null))
        if (
            !preferences
                .edit()
                .putString("enabledOwner", owner)
                .putString("enabledSession", session)
                .commit()
        )
            throw IOException("Could not enable demo tapping")
    }

    // Bind consent to this encrypted session; login or refresh requires enabling tapping again.
    fun enabled(owner: String) =
        preferences.getString("enabledOwner", null) == owner &&
            auth.getString("refresh", null) != null &&
            preferences.getString("enabledSession", null) == auth.getString("refresh", null)

    fun readyBinding(): CardBinding? {
        val owner = auth.getString("userId", null) ?: return null
        if (!enabled(owner)) return null
        return read(owner)?.takeIf { it.status == "ACTIVE" && it.cardId != null }
    }

    fun reset(owner: String) {
        pause()
        if (!preferences.edit().remove("binding:$owner").commit())
            throw IOException("Could not reset card setup")
    }

    fun recordTap(owner: String) {
        preferences.edit().putString("lastTap:$owner", java.time.Instant.now().toString()).apply()
    }

    fun lastTap(owner: String) = preferences.getString("lastTap:$owner", null)
}
