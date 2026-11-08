package com.tapflow.card.ui

import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.nfc.NfcManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tapflow.androidauth.MobileAuthRepository
import com.tapflow.androidauth.MobileUser
import com.tapflow.card.data.*
import java.math.BigDecimal

private fun money(minor: Long) = "A$" + BigDecimal.valueOf(minor, 2).toPlainString()

@Composable
fun CardScreen(user: MobileUser, auth: MobileAuthRepository) {
    val context = LocalContext.current.applicationContext
    val local = remember { CardLocalStore(context) }
    val model: CardViewModel =
        viewModel(key = "card:${user.id}") { CardViewModel(CardRepository(user.id, auth, local)) }
    val state by model.state.collectAsStateWithLifecycle()
    var label by remember { mutableStateOf("My demo card") }
    DisposableEffect(model) { onDispose { model.clear() } }
    LaunchedEffect(user.id) { model.refresh() }
    val snapshot = state.snapshot
    val binding = snapshot?.binding
    val adapter = context.getSystemService(NfcManager::class.java)?.defaultAdapter
    val hce =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
    val unlocked = !context.getSystemService(KeyguardManager::class.java).isDeviceLocked
    val hardwareReady = hce && adapter?.isEnabled == true && unlocked
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Your demo wallet",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        if (snapshot == null) {
            Text("Connect to load your cards and wallet.")
            OutlinedButton(onClick = model::refresh, enabled = !state.busy) { Text("Try again") }
        } else {
            if (snapshot.balance != null) {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Demo balance · AUD", style = MaterialTheme.typography.labelLarge)
                        Text(
                            money(snapshot.balance),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(binding?.label.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text(binding?.status.orEmpty(), style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Card ending ${binding?.cardId?.takeLast(4).orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (state.error != null)
                    Text(
                        "Last loaded balance. Refresh to check the current amount.",
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
            if (binding == null || binding.cardId == null) {
                Text(
                    if (binding == null)
                        "Enroll this device to create a demo card. It receives demo funds once."
                    else "Setup is pending. Retry with the same saved device key and label."
                )
                OutlinedTextField(
                    value = binding?.label ?: label,
                    onValueChange = { label = it.take(80) },
                    enabled = binding == null && !state.busy,
                    label = { Text("Card label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { model.enroll(binding?.label ?: label) },
                    enabled = !state.busy && (binding == null || snapshot.keyPresent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (binding == null) "Enroll demo card" else "Retry enrollment")
                }
            }
            OutlinedButton(
                onClick = model::refresh,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Refresh wallet")
            }
            Text("Demo tapping", style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    !hce || adapter == null ->
                        "NFC card emulation is unavailable on this device. Enrollment and wallet access still work."
                    !adapter.isEnabled -> "Turn on NFC in Android settings, then refresh."
                    !unlocked -> "Unlock this device before enabling demo tapping."
                    binding == null || binding.cardId == null -> "Enroll this device first."
                    !snapshot.keyPresent ->
                        "Device key is missing. Disable the old card, then create a replacement."
                    binding.status != "ACTIVE" -> "This card is blocked."
                    snapshot.tapping -> "Demo tapping enabled while unlocked."
                    else -> "Demo tapping is paused."
                }
            )
            if (binding?.status == "ACTIVE" && snapshot.keyPresent) {
                if (snapshot.tapping)
                    OutlinedButton(onClick = model::pause, enabled = !state.busy) {
                        Text("Pause demo tapping")
                    }
                else
                    Button(onClick = model::enable, enabled = hardwareReady && !state.busy) {
                        Text("Enable demo tapping")
                    }
            }
            Text(
                "A signed NFC challenge is not a payment approval. Server authorization comes later.",
                style = MaterialTheme.typography.bodySmall,
            )
            snapshot.lastTap?.let {
                Text("Last challenge signed: $it", style = MaterialTheme.typography.bodySmall)
            }
            snapshot.cards
                .filter { it.status == "ACTIVE" }
                .forEach { card ->
                    OutlinedButton(
                        onClick = { model.disable(card.id) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (card.id == binding?.cardId) "Disable this card"
                            else "Disable ${card.label} · ${card.id.takeLast(4)}"
                        )
                    }
                }
            if (binding != null && (!snapshot.keyPresent || binding.status == "BLOCKED")) {
                Text(
                    "A replacement creates a new card. The old card must be disabled first; its key and funds are not transferred.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = model::reset,
                    enabled =
                        !state.busy &&
                            snapshot.cards.none {
                                it.status == "ACTIVE" &&
                                    (binding.cardId == null || it.id == binding.cardId)
                            },
                ) {
                    Text("Create replacement setup")
                }
            }
            if (snapshot.activity.isNotEmpty()) {
                Text("Wallet activity", style = MaterialTheme.typography.titleLarge)
                snapshot.activity.forEach { entry ->
                    ListItem(
                        headlineContent = {
                            Text(if (entry.type == "DEMO_FUNDING") "Demo funding" else entry.type)
                        },
                        supportingContent = { Text(entry.createdAt) },
                        trailingContent = { Text(money(entry.amountMinor)) },
                    )
                }
                if (snapshot.nextCursor != null)
                    TextButton(onClick = model::more, enabled = !state.busy) {
                        Text("Load more activity")
                    }
            }
        }
    }
}
