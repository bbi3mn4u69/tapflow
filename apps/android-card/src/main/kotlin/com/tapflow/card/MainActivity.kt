package com.tapflow.card

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tapflow.androidauth.AccountScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AccountScreen("Customer Card", "CUSTOMER", BuildConfig.API_BASE_URL, BuildConfig.DEBUG) }
    }
}
