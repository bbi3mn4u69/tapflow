package com.tapflow.androidauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

class AuthFailure(val status: Int) : Exception(when (status) {
    401 -> "Your session ended. Sign in again."
    403 -> "This account cannot access this app."
    429 -> "Too many attempts. Please wait before trying again."
    else -> "Could not connect. Please try again."
})
data class MobileMembership(val merchantId: String, val merchantName: String, val role: String)
data class MobileUser(val id: String, val email: String, val accountType: String, val memberships: List<MobileMembership>)
class MobileTokens(val access: String, val refresh: String, val user: MobileUser) {
    override fun toString() = "MobileTokens([redacted])"
}

class MobileAuthApi(private val baseUrl: String, allowEmulatorHttp: Boolean = false) {
    init {
        val url = URI(baseUrl)
        require(url.host != null && url.userInfo == null && url.query == null && url.fragment == null && url.path == "/api/v1") { "Configure an API base URL ending in /api/v1" }
        require(url.scheme == "https" || (allowEmulatorHttp && url.scheme == "http" && url.host == "10.0.2.2")) { "The API must use HTTPS" }
    }
    suspend fun login(email: String, password: String): MobileTokens = tokens(request("/auth/mobile/login", JSONObject().put("email", email).put("password", password)))
    suspend fun refresh(raw: String): MobileTokens = tokens(request("/auth/mobile/refresh", JSONObject().put("refreshToken", raw)))
    suspend fun logout(access: String, refresh: String) { request("/auth/mobile/logout", JSONObject().put("refreshToken", refresh), access) }
    suspend fun me(access: String): MobileUser = user(request("/me", access = access))

    private fun tokens(json: JSONObject): MobileTokens {
        check(json.getBoolean("demo") && json.getInt("expiresInSeconds") == 900) { "Invalid demo session" }
        val access = json.getString("accessToken")
        val refresh = json.getString("refreshToken")
        check(access.isNotBlank() && refresh.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid session" }
        return MobileTokens(access, refresh, user(json.getJSONObject("user")))
    }
    private fun user(json: JSONObject): MobileUser {
        val id = UUID.fromString(json.getString("id")).toString()
        val account = json.getString("accountType")
        check(account in setOf("CUSTOMER", "MERCHANT", "SYSTEM_ADMIN")) { "Invalid account" }
        val memberships = json.getJSONArray("memberships")
        return MobileUser(id, json.getString("email"), account, (0 until memberships.length()).map {
            val row = memberships.getJSONObject(it)
            val role = row.getString("role")
            check(role in setOf("OWNER", "OPERATOR")) { "Invalid membership" }
            MobileMembership(UUID.fromString(row.getString("merchantId")).toString(), row.getString("merchantName"), role)
        })
    }
    private suspend fun request(path: String, body: JSONObject? = null, access: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Request-ID", UUID.randomUUID().toString())
            connection.setRequestProperty("Cache-Control", "no-store")
            if (access != null) connection.setRequestProperty("Authorization", "Bearer $access")
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw AuthFailure(status)
            if (status == 204) return@withContext JSONObject()
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(4096)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    check(output.size() + read <= 65_536) { "Response too large" }
                    output.write(buffer, 0, read)
                }
            }
            JSONObject(output.toString(Charsets.UTF_8.name()))
        } finally { connection.disconnect() }
    }
}
