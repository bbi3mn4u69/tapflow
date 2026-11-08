package com.tapflow.pos.ui

import android.nfc.Tag
import android.nfc.tech.IsoDep
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapflow.androidauth.AuthFailure
import com.tapflow.pos.data.*
import com.tapflow.protocol.*
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PosUiState(
    val busy: Boolean = false,
    val snapshot: PosSnapshot? = null,
    val verified: Boolean = false,
    val phase: ReaderSession.Phase = ReaderSession.Phase.IDLE,
    val cardId: String? = null,
    val error: String? = null,
)

class PosViewModel(private val repository: PosRepository) : ViewModel() {
    private val mutable = MutableStateFlow(PosUiState())
    val state = mutable.asStateFlow()
    private val reader = ReaderSession()
    private val transport = AtomicReference<IsoDep?>(null)
    private var readJob: Job? = null
    private var diagnosticStart: Job? = null
    private var expiryJob: Job? = null

    fun refresh() = run { repository.refresh() }

    fun pair(code: String, label: String) = run { repository.pair(code, label) }

    fun clearPending() = run { repository.clearPending() }

    private fun run(action: suspend () -> PosSnapshot) {
        if (mutable.value.busy) return
        cancelDiagnostic()
        mutable.value = mutable.value.copy(busy = true, error = null, verified = false)
        viewModelScope.launch {
            try {
                mutable.value = PosUiState(snapshot = action(), verified = true)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val pending =
                    try {
                        repository.pending()
                    } catch (_: IOException) {
                        null
                    }
                mutable.value =
                    mutable.value.copy(
                        busy = false,
                        verified = false,
                        snapshot =
                            mutable.value.snapshot?.copy(pending = pending)
                                ?: PosSnapshot(null, pending),
                        error =
                            when (failure) {
                                is AuthFailure -> failure.message
                                is IllegalStateException,
                                is IllegalArgumentException,
                                is IOException -> failure.message ?: "Could not load this terminal."
                                else ->
                                    "Could not refresh this terminal. Check your connection and retry."
                            },
                    )
            }
        }
    }

    fun startDiagnostic(amount: Long) {
        if (mutable.value.busy) return
        cancelDiagnostic()
        mutable.value = mutable.value.copy(busy = true, error = null, verified = false)
        diagnosticStart = viewModelScope.launch {
            try {
                val snapshot = repository.refresh()
                val terminal = checkNotNull(snapshot.terminal)
                check(terminal.status == "ACTIVE") { "This terminal is disabled." }
                val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                reader.start(
                    Challenge(
                        UUID.randomUUID(),
                        UUID.fromString(terminal.merchantId),
                        amount,
                        nonce,
                        System.currentTimeMillis() + 90000,
                    )
                )
                mutable.value =
                    PosUiState(snapshot = snapshot, verified = true, phase = reader.phase)
                expiryJob = viewModelScope.launch {
                    while (
                        isActive &&
                            reader.phase in
                                setOf(ReaderSession.Phase.WAITING, ReaderSession.Phase.READING)
                    ) {
                        delay(1000)
                        if (
                            reader.tick(System.currentTimeMillis()) == ReaderSession.Phase.EXPIRED
                        ) {
                            readJob?.cancel()
                            closeActive()
                            mutable.value = mutable.value.copy(phase = reader.phase)
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutable.value =
                    mutable.value.copy(
                        busy = false,
                        error =
                            "Could not start the connection test. Refresh terminal status and retry.",
                    )
            }
        }
    }

    fun read(tag: Tag) {
        viewModelScope.launch {
            val ticket =
                reader.begin(System.currentTimeMillis())
                    ?: run {
                        mutable.value = mutable.value.copy(phase = reader.phase)
                        return@launch
                    }
            mutable.value = mutable.value.copy(phase = reader.phase, error = null)
            readJob = launch {
                try {
                    val proof =
                        withContext(Dispatchers.IO) {
                            val iso =
                                IsoDep.get(tag)
                                    ?: throw IOException("Use an unlocked TapFlow Card phone.")
                            transport.set(iso)
                            try {
                                currentCoroutineContext().ensureActive()
                                iso.connect()
                                iso.timeout = 3000
                                val context = currentCoroutineContext()
                                ReaderExchange.read(ticket.challenge) { bytes ->
                                    context.ensureActive()
                                    iso.transceive(bytes)
                                }
                            } finally {
                                runCatching { iso.close() }
                                transport.compareAndSet(iso, null)
                            }
                        }
                    if (reader.complete(ticket, System.currentTimeMillis()))
                        mutable.value =
                            mutable.value.copy(
                                phase = reader.phase,
                                cardId = proof.cardId.toString(),
                            )
                    else mutable.value = mutable.value.copy(phase = reader.phase)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (reader.retry(ticket, System.currentTimeMillis()))
                        mutable.value =
                            mutable.value.copy(
                                phase = reader.phase,
                                error =
                                    if (failure is IllegalArgumentException)
                                        "The card response is incompatible. Unlock the Card phone and enable demo tapping, then tap again."
                                    else "Tap again. Keep the two unlocked phones together.",
                            )
                    else mutable.value = mutable.value.copy(phase = reader.phase)
                }
            }
        }
    }

    fun cancelDiagnostic() {
        val wasStarting = diagnosticStart?.isActive == true
        diagnosticStart?.cancel()
        diagnosticStart = null
        reader.cancel()
        readJob?.cancel()
        readJob = null
        expiryJob?.cancel()
        expiryJob = null
        closeActive()
        mutable.value =
            mutable.value.copy(
                phase = reader.phase,
                cardId = null,
                busy = if (wasStarting) false else mutable.value.busy,
            )
    }

    fun clear() {
        cancelDiagnostic()
        viewModelScope.coroutineContext.cancelChildren()
        mutable.value = PosUiState()
    }

    private fun closeActive() {
        // Teardown must unblock NFC even after viewModelScope has been cancelled.
        transport.getAndSet(null)?.let { iso ->
            CoroutineScope(Dispatchers.IO).launch { runCatching { iso.close() } }
        }
    }

    override fun onCleared() {
        reader.cancel()
        closeActive()
        super.onCleared()
    }
}
