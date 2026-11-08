package com.tapflow.pos.ui

import android.nfc.NfcAdapter
import android.nfc.NfcManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tapflow.androidauth.MobileAuthRepository
import com.tapflow.androidauth.MobileUser
import com.tapflow.pos.BuildConfig
import com.tapflow.pos.data.*
import com.tapflow.protocol.ReaderSession
import java.math.BigDecimal

@Composable
fun PosScreen(user: MobileUser, auth: MobileAuthRepository, activity: ComponentActivity) {
    val context = LocalContext.current.applicationContext
    val model: PosViewModel =
        viewModel(key = "pos:${user.id}") {
            PosViewModel(PosRepository(user, auth, PosLocalStore(context)))
        }
    val state by model.state.collectAsStateWithLifecycle()
    val adapter = context.getSystemService(NfcManager::class.java)?.defaultAdapter
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var code by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("Front counter") }
    var amount by remember { mutableStateOf("") }
    val pending = state.snapshot?.pending
    val terminal = state.snapshot?.terminal
    val minor = AmountInput.parse(amount)
    DisposableEffect(model) { onDispose { model.clear() } }
    LaunchedEffect(user.id) { model.refresh() }
    DisposableEffect(activity, lifecycle, adapter, state.phase) {
        fun enable() {
            if (
                adapter != null &&
                    state.phase == ReaderSession.Phase.WAITING &&
                    adapter.isEnabled &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            )
                runCatching {
                    adapter.enableReaderMode(
                        activity,
                        { tag -> model.read(tag) },
                        NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                        null,
                    )
                }
                    .onFailure { model.cancelDiagnostic() }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enable()
            if (event == Lifecycle.Event.ON_PAUSE) {
                runCatching { adapter?.disableReaderMode(activity) }
                model.cancelDiagnostic()
            }
        }
        lifecycle.addObserver(observer)
        enable()
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { adapter?.disableReaderMode(activity) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        if (terminal == null) {
            Text("Pair this terminal", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Ask a merchant owner for a one-time code from the portal. Codes expire after 10 minutes."
            )
            OutlinedTextField(
                value = if (pending != null) "Saved securely" else code,
                onValueChange = { code = it.take(32) },
                enabled = pending == null && !state.busy,
                label = { Text("Pairing code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pending?.label ?: label,
                onValueChange = { label = it.take(80) },
                enabled = pending == null && !state.busy,
                label = { Text("Terminal name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    model.pair(code, label)
                    code = ""
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(if (pending == null) "Pair terminal" else "Retry pairing")
            }
            if (pending != null || state.error != null) {
                Text(
                    "Retry uses the same encrypted code and saved request. Clearing it does not unpair a registered terminal.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = model::clearPending, enabled = !state.busy) {
                    Text("Clear pending pairing")
                }
            }
        } else {
            Text(terminal.label, style = MaterialTheme.typography.headlineMedium)
            Text(
                user.memberships.first { it.merchantId == terminal.merchantId }.merchantName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(terminal.status, style = MaterialTheme.typography.labelLarge)
            Text("Checkout", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.take(10) },
                label = { Text("Amount in AUD") },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                singleLine = true,
                isError = amount.isNotEmpty() && minor == null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (amount.isNotEmpty() && minor == null)
                Text(
                    "Enter A$0.01–A$10,000.00 with up to two decimal places.",
                    color = MaterialTheme.colorScheme.error,
                )
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Charge " +
                        (minor?.let { "A$" + BigDecimal.valueOf(it, 2).toPlainString() }
                            ?: "amount")
                )
            }
            Text(
                "Payments are not available in this build. No payment will be submitted.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (adapter == null)
                    "NFC is unavailable on this device. Pairing and terminal status still work."
                else if (!adapter.isEnabled)
                    "Turn on NFC in Android settings, then refresh this screen."
                else "NFC reader available."
            )
            if (BuildConfig.DEBUG) {
                Text("Connection test", style = MaterialTheme.typography.titleLarge)
                Text(
                    "This diagnostic signs a local test challenge. It does not create a payment intent or authorize a payment.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (
                    state.phase == ReaderSession.Phase.IDLE ||
                        state.phase == ReaderSession.Phase.RECEIVED ||
                        state.phase == ReaderSession.Phase.EXPIRED
                )
                    OutlinedButton(
                        onClick = { minor?.let(model::startDiagnostic) },
                        enabled =
                            !state.busy &&
                                state.verified &&
                                terminal.status == "ACTIVE" &&
                                minor != null &&
                                adapter?.isEnabled == true,
                    ) {
                        Text("Test NFC connection")
                    }
                when (state.phase) {
                    ReaderSession.Phase.WAITING ->
                        Text("HOLD PHONES TOGETHER", style = MaterialTheme.typography.titleMedium)
                    ReaderSession.Phase.READING -> Text("Reading card…")
                    ReaderSession.Phase.RECEIVED -> {
                        Text("Card response received")
                        Text("Card ID: ${state.cardId}", style = MaterialTheme.typography.bodySmall)
                        Text("Server signature verification is pending. No payment submitted.")
                    }
                    ReaderSession.Phase.EXPIRED ->
                        Text("Connection test expired. Start a new test.")
                    else -> Unit
                }
                if (
                    state.phase == ReaderSession.Phase.WAITING ||
                        state.phase == ReaderSession.Phase.READING
                )
                    OutlinedButton(onClick = model::cancelDiagnostic) {
                        Text("Cancel connection test")
                    }
            }
        }
        OutlinedButton(
            onClick = model::refresh,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Refresh terminal")
        }
    }
}
