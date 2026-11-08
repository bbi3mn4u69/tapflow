package com.tapflow.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tapflow.androidauth.AccountScreen
import com.tapflow.pos.ui.PosScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AccountScreen(
                "Merchant POS",
                "MERCHANT",
                BuildConfig.API_BASE_URL,
                BuildConfig.DEBUG,
            ) { user, auth ->
                PosScreen(user, auth, this)
            }
        }
    }
}
