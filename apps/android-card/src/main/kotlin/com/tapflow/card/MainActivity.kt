package com.tapflow.card

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tapflow.androidauth.AccountScreen
import com.tapflow.card.data.CardLocalStore
import com.tapflow.card.ui.CardScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AccountScreen(
                "Customer Card",
                "CUSTOMER",
                BuildConfig.API_BASE_URL,
                BuildConfig.DEBUG,
                onSignOut = {
                    withContext(Dispatchers.IO) { CardLocalStore(applicationContext).pause() }
                },
            ) { user, auth ->
                CardScreen(user, auth)
            }
        }
    }
}
