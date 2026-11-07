package com.tapflow.androidauth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(val busy: Boolean = true, val user: MobileUser? = null, val error: String? = null)

class AuthViewModel(private val repository: MobileAuthRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthUiState())
    val state = mutableState.asStateFlow()
    init { restore() }
    fun restore() = run { repository.restore() }
    fun login(email: String, password: String) {
        if (mutableState.value.busy) return
        if (email.trim().length !in 3..254 || !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() || password.length !in 1..128) {
            mutableState.value = AuthUiState(error = "Enter a valid email and your password (up to 128 characters).", busy = false)
            return
        }
        run(login = true) { repository.login(email, password) }
    }
    fun logout() { if (!mutableState.value.busy) run { repository.logout(); null } }
    private fun run(login: Boolean = false, action: suspend () -> MobileUser?) {
        val previous = mutableState.value.user
        mutableState.value = AuthUiState(busy = true, user = previous)
        viewModelScope.launch {
            try { mutableState.value = AuthUiState(busy = false, user = action()) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = AuthUiState(busy = false, user = previous,
                    error = if (login && failure is AuthFailure && failure.status == 401) "Email or password is incorrect."
                        else if (failure is AuthFailure) failure.message else "Could not connect. Please try again.")
            }
        }
    }
}
