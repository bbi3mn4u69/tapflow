package com.tapflow.androidauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MobileAuthRepository(private val api: MobileAuthApi, private val store: RefreshTokenStore, private val accountType: String) {
    private val mutex = Mutex()
    private var access: String? = null

    suspend fun login(email: String, password: String): MobileUser = mutex.withLock {
        accept(api.login(email.trim(), password))
    }
    suspend fun restore(): MobileUser? = mutex.withLock {
        val raw = withContext(Dispatchers.IO) { store.read() } ?: return@withLock null
        try {
            accept(api.refresh(raw))
            api.me(checkNotNull(access))
        } catch (error: AuthFailure) {
            if (error.status == 401 || error.status == 403) {
                clear()
                null
            } else throw error
        }
    }
    suspend fun logout() = mutex.withLock {
        val raw = withContext(Dispatchers.IO) { store.read() }
        if (raw != null) {
            var token = access
            if (token == null) { val refreshed = api.refresh(raw); accept(refreshed); token = refreshed.access }
            try {
                api.logout(checkNotNull(token), withContext(Dispatchers.IO) { store.read() } ?: raw)
            } catch (failure: AuthFailure) {
                if (failure.status != 401) throw failure
                // An expired access token needs one rotation before server-side logout.
                try {
                    val refreshed = api.refresh(withContext(Dispatchers.IO) { store.read() } ?: raw)
                    api.logout(refreshed.access, refreshed.refresh)
                } catch (refreshFailure: AuthFailure) { if (refreshFailure.status != 401) throw refreshFailure }
            }
        }
        clear()
    }
    private suspend fun accept(tokens: MobileTokens): MobileUser {
        if (tokens.user.accountType != accountType) {
            try { api.logout(tokens.access, tokens.refresh) } finally { clear() }
            throw AuthFailure(403)
        }
        try { withContext(Dispatchers.IO) { store.save(tokens.refresh) } }
        catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            try { api.logout(tokens.access, tokens.refresh) } finally { clear() }
            throw failure
        }
        access = tokens.access
        return tokens.user
    }
    private suspend fun clear() { access = null; withContext(Dispatchers.IO) { store.clear() } }
}
