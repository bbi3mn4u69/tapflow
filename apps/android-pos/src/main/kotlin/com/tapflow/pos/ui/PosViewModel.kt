package com.tapflow.pos.ui

import android.nfc.Tag
import android.nfc.tech.IsoDep
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tapflow.androidauth.AuthFailure
import com.tapflow.pos.data.*
import com.tapflow.protocol.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PaymentPhase {
    IDLE,
    CREATING,
    WAITING,
    READING,
    AUTHORISING,
    RECOVERING,
    APPROVED,
    DECLINED,
    CANCELLED,
    EXPIRED,
}

data class PosUiState(
    val busy: Boolean = false,
    val snapshot: PosSnapshot? = null,
    val verified: Boolean = false,
    val sale: SaleView? = null,
    val phase: PaymentPhase = PaymentPhase.IDLE,
    val error: String? = null,
)

class PosViewModel(private val repository: PosRepository, private val sales: SaleRepository) :
    ViewModel() {
    private val mutable = MutableStateFlow(PosUiState())
    val state = mutable.asStateFlow()
    private val reader = ReaderSession()
    private val transport = AtomicReference<IsoDep?>(null)
    private var readJob: Job? = null
    private var expiryJob: Job? = null
    private var foreground = false
    private val finals = setOf("APPROVED", "DECLINED", "CANCELLED", "EXPIRED")

    fun refresh() =
        operation(PaymentPhase.RECOVERING) {
            val snapshot = repository.refresh()
            mutable.value = mutable.value.copy(snapshot = snapshot, verified = true)
            show(sales.recover())
        }

    fun pair(code: String, label: String) =
        operation(PaymentPhase.IDLE) {
            val snapshot = repository.pair(code, label)
            mutable.value = mutable.value.copy(snapshot = snapshot, verified = true)
            show(sales.recover())
        }

    fun clearPending() =
        operation(PaymentPhase.IDLE) {
            val snapshot = repository.clearPending()
            mutable.value = mutable.value.copy(snapshot = snapshot, verified = true)
            show(sales.recover())
        }

    fun startSale(amount: Long) =
        operation(PaymentPhase.CREATING) {
            val snapshot = repository.refresh()
            mutable.value = mutable.value.copy(snapshot = snapshot, verified = true)
            show(sales.start(checkNotNull(snapshot.terminal), amount))
        }

    fun checkStatus() = operation(PaymentPhase.RECOVERING) { show(sales.recover()) }

    fun retrySubmission() = operation(PaymentPhase.AUTHORISING) { show(sales.retrySubmission()) }

    fun cancelSale() = operation(PaymentPhase.RECOVERING) { show(sales.cancel()) }

    fun newSale() =
        operation(PaymentPhase.IDLE) {
            sales.acknowledge()
            show(null)
        }

    private fun operation(phase: PaymentPhase, action: suspend () -> Unit) {
        if (mutable.value.busy) return
        stopReader()
        mutable.value = mutable.value.copy(busy = true, phase = phase, error = null)
        viewModelScope.launch {
            try {
                action()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val saved =
                    try {
                        sales.saved()
                    } catch (_: Exception) {
                        null
                    }
                val pending =
                    try {
                        repository.pending()
                    } catch (_: Exception) {
                        null
                    }
                val final = saved?.status?.takeIf { it in finals }
                mutable.value =
                    mutable.value.copy(
                        busy = false,
                        sale = saved ?: mutable.value.sale,
                        snapshot =
                            mutable.value.snapshot?.copy(pending = pending)
                                ?: PosSnapshot(null, pending),
                        phase =
                            if (final != null) PaymentPhase.valueOf(final)
                            else if (saved != null || mutable.value.sale != null)
                                PaymentPhase.RECOVERING
                            else PaymentPhase.IDLE,
                        error =
                            when (failure) {
                                is AuthFailure -> failure.message
                                is IllegalStateException,
                                is IllegalArgumentException,
                                is IOException -> failure.message ?: "Could not check this payment."
                                else ->
                                    "Could not connect. Check the existing payment status before starting another."
                            },
                    )
            }
        }
    }

    private fun show(sale: SaleView?) {
        val phase =
            when {
                sale == null -> PaymentPhase.IDLE
                sale.status in finals -> PaymentPhase.valueOf(sale.status)
                sale.record.proof != null ||
                    sale.record.cancelRequested ||
                    !foreground ||
                    sale.challenge == null ||
                    sale.challenge.expiresAtEpochMillis <= System.currentTimeMillis() ->
                    PaymentPhase.RECOVERING
                else -> PaymentPhase.WAITING
            }
        mutable.value = mutable.value.copy(busy = false, sale = sale, phase = phase, error = null)
        if (phase == PaymentPhase.WAITING && mutable.value.snapshot?.terminal?.status == "ACTIVE") {
            reader.start(checkNotNull(sale?.challenge))
            expiryJob = viewModelScope.launch {
                while (
                    isActive &&
                        reader.phase in
                            setOf(ReaderSession.Phase.WAITING, ReaderSession.Phase.READING)
                ) {
                    delay(1000)
                    if (reader.tick(System.currentTimeMillis()) == ReaderSession.Phase.EXPIRED) {
                        stopReader()
                        checkStatus()
                    }
                }
            }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) {
            stopReader()
            if (mutable.value.phase in setOf(PaymentPhase.WAITING, PaymentPhase.READING))
                mutable.value = mutable.value.copy(phase = PaymentPhase.RECOVERING)
        } else if (
            !mutable.value.busy &&
                mutable.value.sale != null &&
                mutable.value.phase == PaymentPhase.RECOVERING
        )
            checkStatus()
    }

    fun read(tag: Tag) {
        viewModelScope.launch {
            if (!foreground || mutable.value.phase != PaymentPhase.WAITING) return@launch
            val ticket = reader.begin(System.currentTimeMillis()) ?: return@launch
            mutable.value = mutable.value.copy(phase = PaymentPhase.READING, error = null)
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
                    if (reader.complete(ticket, System.currentTimeMillis())) {
                        readJob = null
                        operation(PaymentPhase.AUTHORISING) { show(sales.authorise(proof)) }
                    } else {
                        readJob = null
                        checkStatus()
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (reader.retry(ticket, System.currentTimeMillis()))
                        mutable.value =
                            mutable.value.copy(
                                phase = PaymentPhase.WAITING,
                                error =
                                    "Tap again against this same payment. Unlock the Card phone and keep both phones together.",
                            )
                    else {
                        readJob = null
                        checkStatus()
                    }
                }
            }
        }
    }

    private fun stopReader() {
        reader.cancel()
        readJob?.cancel()
        readJob = null
        expiryJob?.cancel()
        expiryJob = null
        closeActive()
    }

    private fun closeActive() {
        // Cleanup must unblock platform I/O even after the ViewModel scope is cancelled.
        transport.getAndSet(null)?.let { iso ->
            CoroutineScope(Dispatchers.IO).launch { runCatching { iso.close() } }
        }
    }

    fun clear() {
        stopReader()
        viewModelScope.coroutineContext.cancelChildren()
        foreground = false
        mutable.value = PosUiState()
    }

    override fun onCleared() {
        reader.cancel()
        closeActive()
        super.onCleared()
    }
}
