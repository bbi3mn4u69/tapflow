package com.tapflow.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PosWelcomeScreen() }
    }
}

@Preview(showBackground = true)
@Composable
private fun PosWelcomeScreen() {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF214B3E))) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF5F7F2)) {
            Box(modifier = Modifier.safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text("tapflow.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("DEMO / NO REAL MONEY", style = MaterialTheme.typography.labelMedium, color = Color(0xFF436653))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("MERCHANT TERMINAL", style = MaterialTheme.typography.labelMedium, color = Color(0xFF52665C))
                        Text("Ready for setup.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium)
                        Text("A focused checkout for your merchant, connected to the same demo system.", style = MaterialTheme.typography.bodyLarge)
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF214B3E), RoundedCornerShape(24.dp)).padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        Text("TAPFLOW POINT OF SALE", style = MaterialTheme.typography.labelMedium, color = Color(0xFFC8DECA))
                        Text("Terminal not paired", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                        Text("SIMULATED VALUE ONLY", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC8DECA))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("The path to your first tap", style = MaterialTheme.typography.titleLarge)
                        Text("01  Sign in as a merchant operator", style = MaterialTheme.typography.bodyLarge)
                        Text("02  Pair this terminal with your store", style = MaterialTheme.typography.bodyLarge)
                        Text("03  Enter an amount and accept a demo tap", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        "Starter build · Pairing, sign-in and payments are not enabled yet. No real money is transferred.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF52665C),
                    )
                }
            }
        }
    }
}
