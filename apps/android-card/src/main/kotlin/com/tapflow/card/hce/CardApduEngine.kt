package com.tapflow.card.hce

import android.app.KeyguardManager
import android.content.Context
import com.tapflow.card.crypto.CardKeyManager
import com.tapflow.card.data.CardLocalStore
import com.tapflow.protocol.*
import java.util.UUID
import kotlinx.coroutines.*

class CardApduEngine(
    context: Context,
    private val respond: (ByteArray) -> Unit,
    private val unlocked: () -> Boolean = {
        !context.getSystemService(KeyguardManager::class.java).isDeviceLocked
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = HceSession()
    private val local = CardLocalStore(context.applicationContext)
    private val keys = CardKeyManager()
    private var pending: Job? = null

    fun command(bytes: ByteArray): ByteArray? {
        return when (val action = session.handle(bytes, unlocked())) {
            is HceSession.Action.Reply -> {
                pending?.cancel()
                action.bytes
            }
            is HceSession.Action.Sign -> {
                pending =
                    scope.launch(Dispatchers.IO) {
                        var owner: String? = null
                        val response =
                            try {
                                val binding = local.readyBinding()
                                if (binding == null || !keys.exists(binding.alias) || !unlocked())
                                    ApduCodec.status(ApduCodec.NOT_READY)
                                else {
                                    owner = binding.owner
                                    val id = UUID.fromString(checkNotNull(binding.cardId))
                                    val signature =
                                        keys.sign(
                                            binding.alias,
                                            ChallengeCodec.canonical(action.challenge, id),
                                        )
                                    val current = local.readyBinding()
                                    if (
                                        current?.cardId != binding.cardId ||
                                            current?.alias != binding.alias ||
                                            !unlocked()
                                    )
                                        ApduCodec.status(ApduCodec.NOT_READY)
                                    else ApduCodec.signResponse(id, signature)
                                }
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                ApduCodec.status(ApduCodec.INTERNAL)
                            }
                        withContext(Dispatchers.Main.immediate) {
                            val final =
                                if (unlocked()) response else ApduCodec.status(ApduCodec.NOT_READY)
                            session.complete(action.generation, final)?.let {
                                respond(it)
                                if (it.size > 2 && owner != null)
                                    scope.launch(Dispatchers.IO) { local.recordTap(owner!!) }
                            }
                        }
                    }
                null
            }
        }
    }

    fun deactivate() {
        session.deactivate()
        pending?.cancel()
        pending = null
    }

    fun close() {
        deactivate()
        scope.cancel()
    }
}
