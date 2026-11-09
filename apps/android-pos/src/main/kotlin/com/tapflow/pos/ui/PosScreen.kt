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
import com.tapflow.androidauth.*
import com.tapflow.pos.data.*
import java.math.BigDecimal
import kotlinx.coroutines.delay

private fun money(value: Long) = "A$" + BigDecimal.valueOf(value, 2).toPlainString()

@Composable
fun PosScreen(user: MobileUser, auth: MobileAuthRepository, activity: ComponentActivity) {
    val context = LocalContext.current.applicationContext
    val model: PosViewModel =
        viewModel(key = "pos:${user.id}") {
            val local = PosLocalStore(context)
            PosViewModel(PosRepository(user, auth, local), SaleRepository(user, auth, local))
        }
    val state by model.state.collectAsStateWithLifecycle()
    val adapter = context.getSystemService(NfcManager::class.java)?.defaultAdapter
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var code by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("Front counter") }
    var amount by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val pending = state.snapshot?.pending
    val terminal = state.snapshot?.terminal
    val sale = state.sale
    val minor = AmountInput.parse(amount)
    DisposableEffect(model, activity) {
        onDispose { if (!activity.isChangingConfigurations) model.clear() }
    }
    LaunchedEffect(user.id) { model.refresh() }
    LaunchedEffect(sale?.record?.createKey) {
        amount = ""
        while (sale != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    DisposableEffect(activity, lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.setForeground(true)
            if (event == Lifecycle.Event.ON_PAUSE) model.setForeground(false)
        }
        lifecycle.addObserver(observer)
        model.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(activity, adapter, state.phase, terminal?.status) {
        if (
            adapter != null &&
                adapter.isEnabled &&
                state.phase == PaymentPhase.WAITING &&
                terminal?.status == "ACTIVE" &&
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
                .onFailure { model.setForeground(false) }
        onDispose { runCatching { adapter?.disableReaderMode(activity) } }
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
        if (terminal == null && sale == null) {
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
                    "Retry reuses the saved encrypted setup. Clearing it does not unpair a server terminal.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = model::clearPending, enabled = !state.busy) {
                    Text("Clear pending pairing")
                }
            }
        } else {
            terminal?.let {
                Text(it.label, style = MaterialTheme.typography.headlineMedium)
                Text(
                    user.memberships
                        .firstOrNull { m -> m.merchantId == it.merchantId }
                        ?.merchantName ?: "Merchant workspace",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(it.status, style = MaterialTheme.typography.labelLarge)
            }
            if (sale == null) {
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
                Button(
                    onClick = { minor?.let(model::startSale) },
                    enabled =
                        !state.busy &&
                            state.verified &&
                            terminal?.status == "ACTIVE" &&
                            minor != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Charge " + (minor?.let(::money) ?: "amount"))
                }
            } else {
                Text(money(sale.record.amount), style = MaterialTheme.typography.displaySmall)
                Text(
                    state.phase.name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                sale.record.intent?.optString("id")?.let {
                    Text("Payment $it", style = MaterialTheme.typography.bodySmall)
                }
                when (state.phase) {
                    PaymentPhase.WAITING ->
                        Text(
                            if (adapter?.isEnabled == true && terminal?.status == "ACTIVE")
                                "HOLD PHONES TOGETHER"
                            else
                                "Waiting for a card. NFC is unavailable or this terminal is disabled; you can check status or cancel."
                        )
                    PaymentPhase.READING -> Text("Keep both phones together.")
                    PaymentPhase.AUTHORISING ->
                        Text("Verifying with the server. Do not start another payment.")
                    PaymentPhase.RECOVERING,
                    PaymentPhase.CREATING ->
                        Text(
                            "The outcome is not confirmed. Check this same payment; do not create another sale."
                        )
                    PaymentPhase.APPROVED -> {
                        Text("Payment approved by the demo server.")
                        sale.receipt
                            ?.takeUnless { it.isNull("receiptNumber") }
                            ?.let { Text("Receipt ${it.getString("receiptNumber")}") }
                    }
                    PaymentPhase.DECLINED ->
                        Text(
                            when (sale.receipt?.optString("decisionCode")) {
                                "CARD_BLOCKED" -> "This demo card is blocked."
                                "INSUFFICIENT_FUNDS" -> "The demo wallet has insufficient funds."
                                else -> "Payment declined by the demo server."
                            }
                        )
                    PaymentPhase.CANCELLED -> Text("Payment cancelled. No debit was made.")
                    PaymentPhase.EXPIRED ->
                        Text("The server confirmed that this payment expired. No debit was made.")
                    else -> Unit
                }
                if (state.phase in setOf(PaymentPhase.WAITING, PaymentPhase.READING))
                    sale.challenge?.let {
                        val seconds = ((it.expiresAtEpochMillis - now) / 1000).coerceAtLeast(0)
                        Text(
                            "Time remaining: ${seconds}s",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                OutlinedButton(
                    onClick = model::checkStatus,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Check payment status")
                }
                if (sale.status in setOf("APPROVED", "DECLINED", "CANCELLED", "EXPIRED"))
                    Button(
                        onClick = model::newSale,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("New sale")
                    }
                else {
                    if (sale.record.proof != null && !sale.record.cancelRequested)
                        OutlinedButton(onClick = model::retrySubmission, enabled = !state.busy) {
                            Text("Retry saved submission")
                        }
                    OutlinedButton(
                        onClick = model::cancelSale,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancel payment")
                    }
                }
            }
            Text(
                if (adapter == null)
                    "NFC is unavailable on this device. Payment status and cancellation still work."
                else if (!adapter.isEnabled) "Turn on NFC in Android settings."
                else "NFC reader available.",
                style = MaterialTheme.typography.bodySmall,
            )
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
