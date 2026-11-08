package com.tapflow.androidauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MobileAuthRepository(
    private val api: MobileAuthApi,
    private val store: RefreshTokenStore,
    private val accountType: String,
) {
    private val mutex = Mutex()
    private var access: String? = null
    private val currentUser = MutableStateFlow<MobileUser?>(null)
    val sessionUser = currentUser.asStateFlow()

    suspend fun request(
        path: String,
        body: JSONObject? = null,
        idempotency: String? = null,
    ): JSONObject = mutex.withLock {
        var token = access ?: refreshAccess()
        try {
            api.resource(path, token, body, idempotency)
        } catch (failure: AuthFailure) {
            if (failure.status != 401) throw failure
            token = refreshAccess()
            try {
                api.resource(path, token, body, idempotency)
            } catch (retry: AuthFailure) {
                if (retry.status == 401) clear()
                throw retry
            }
        }
    }

    private suspend fun refreshAccess(): String {
        val raw = withContext(Dispatchers.IO) { store.read() }
        if (raw == null) {
            clear()
            throw AuthFailure(401)
        }
        try {
            accept(api.refresh(raw))
        } catch (failure: AuthFailure) {
            if (failure.status == 401 || failure.status == 403) clear()
            throw failure
        }
        return checkNotNull(access)
    }

    suspend fun login(email: String, password: String): MobileUser = mutex.withLock {
        accept(api.login(email.trim(), password))
    }

    suspend fun restore(): MobileUser? = mutex.withLock {
        val raw =
            withContext(Dispatchers.IO) { store.read() }
                ?: run {
                    clear()
                    return@withLock null
                }
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
            if (token == null) {
                val refreshed = api.refresh(raw)
                accept(refreshed)
                token = refreshed.access
            }
            try {
                api.logout(checkNotNull(token), withContext(Dispatchers.IO) { store.read() } ?: raw)
            } catch (failure: AuthFailure) {
                if (failure.status != 401) throw failure
                // An expired access token needs one rotation before server-side logout.
                try {
                    val refreshed = api.refresh(withContext(Dispatchers.IO) { store.read() } ?: raw)
                    api.logout(refreshed.access, refreshed.refresh)
                } catch (refreshFailure: AuthFailure) {
                    if (refreshFailure.status != 401) throw refreshFailure
                }
            }
        }
        clear()
    }

    private suspend fun accept(tokens: MobileTokens): MobileUser {
        if (tokens.user.accountType != accountType) {
            try {
                api.logout(tokens.access, tokens.refresh)
            } finally {
                clear()
            }
            throw AuthFailure(403)
        }
        try {
            withContext(Dispatchers.IO) { store.save(tokens.refresh, tokens.user.id) }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            try {
                api.logout(tokens.access, tokens.refresh)
            } finally {
                clear()
            }
            throw failure
        }
        access = tokens.access
        currentUser.value = tokens.user
        return tokens.user
    }

    private suspend fun clear() {
        access = null
        currentUser.value = null
        withContext(Dispatchers.IO) { store.clear() }
    }
}
