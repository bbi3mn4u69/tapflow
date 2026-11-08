package com.tapflow.androidauth

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AccountScreen(
    title: String,
    accountType: String,
    baseUrl: String,
    debug: Boolean,
    onSignOut: suspend () -> Unit = {},
    content: (@Composable (MobileUser, MobileAuthRepository) -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val model: AuthViewModel = viewModel {
        AuthViewModel(
            MobileAuthRepository(
                MobileAuthApi(baseUrl, debug),
                RefreshTokenStore(context),
                accountType,
            ),
            onSignOut,
        )
    }
    val state by model.state.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    // Passwords remain in transient UI memory, outside saved state and the ViewModel.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()
    val colors =
        if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.safeDrawingPadding().imePadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier =
                        Modifier.widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text(
                        "tapflow.",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "DEMO / NO REAL MONEY",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.user == null || content == null)
                            Text(
                                if (state.user == null) "Welcome back." else "You’re connected.",
                                style = MaterialTheme.typography.displaySmall,
                            )
                        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        state.error?.let { error ->
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.error,
                                modifier =
                                    Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                            )
                        }
                        val user = state.user
                        if (user == null && !state.busy) {
                            Text(
                                if (accountType == "CUSTOMER")
                                    "Sign in to your customer account to get ready for your first tap."
                                else
                                    "Sign in with your merchant account to access your team’s demo workspace."
                            )
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it.take(254) },
                                label = { Text("Email") },
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Email),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it.take(128) },
                                label = { Text("Password") },
                                visualTransformation =
                                    if (visible) VisualTransformation.None
                                    else PasswordVisualTransformation(),
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                trailingIcon = {
                                    TextButton(onClick = { visible = !visible }) {
                                        Text(if (visible) "Hide password" else "Show password")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    model.login(email, password)
                                    password = ""
                                    visible = false
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text("Sign in")
                            }
                            if (state.error != null)
                                TextButton(onClick = model::restore) { Text("Try again") }
                        } else if (user != null) {
                            if (content != null) {
                                Text(user.email, style = MaterialTheme.typography.bodyMedium)
                                content(user, model.repository)
                            } else
                                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Text(
                                            "Signed in",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            user.email,
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                        if (accountType == "MERCHANT") {
                                            if (user.memberships.isEmpty())
                                                Text(
                                                    "No active merchant workspace is assigned to this account."
                                                )
                                            user.memberships.forEach {
                                                Text(
                                                    "${it.merchantName} · ${if (it.role == "OWNER") "Owner" else "Operator"}"
                                                )
                                            }
                                            Text(
                                                "Terminal pairing and checkout will be available in a later build."
                                            )
                                        } else
                                            Text(
                                                "Device card enrollment will be available in a later build. No wallet is funded yet."
                                            )
                                    }
                                }
                            OutlinedButton(
                                onClick = model::logout,
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text("Sign out")
                            }
                        }
                        Text(
                            "TapFlow is an educational simulator. No real money is stored or transferred.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
