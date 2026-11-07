package com.tapflow.api.auth

import com.tapflow.api.config.DatabaseConfig
import com.tapflow.api.config.AuthConfig
import com.tapflow.api.db.Database
import com.tapflow.api.db.seedDemo
import com.tapflow.api.module
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.*
import io.ktor.server.response.respond
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.postgresql.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthRoutesTest {
    private val postgres = PostgreSQLContainer("postgres@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3")
    private lateinit var db: Database
    private val password = "test-only-long-passphrase"
    private val authConfig = AuthConfig("test-only-secret-at-least-32-bytes-long", "tapflow-test", "tapflow-clients", setOf("https://portal.example.test"))

    @BeforeAll fun start() {
        postgres.start()
        db = Database.open(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password, 3))
        seedDemo(db, password.toCharArray(), password.toCharArray(), password.toCharArray())
    }
    @AfterAll fun stop() { db.close(); postgres.stop() }

    private suspend fun io.ktor.client.HttpClient.login(email: String = "owner@example.test", pass: String = password, web: Boolean = false) = post("/api/v1/auth/${if (web) "web" else "mobile"}/login") {
        contentType(ContentType.Application.Json)
        if (web) { header("Origin", "https://portal.example.test"); header("X-TapFlow-CSRF", "1") }
        setBody(buildJsonObject { put("email", email); put("password", pass) }.toString())
    }
    private fun String.obj() = Json.parseToJsonElement(this).jsonObject
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private suspend fun io.ktor.client.HttpClient.refresh(token: String) = post("/api/v1/auth/mobile/refresh") {
        contentType(ContentType.Application.Json); setBody(buildJsonObject { put("refreshToken", token) }.toString())
    }

    @Test fun `unknown user and wrong password return the same safe error`() = testApplication {
        application { module(db, auth = AuthService(db, authConfig)) }
        val unknown = client.login("absent@example.test")
        val wrong = client.login(pass = "wrong-passphrase")
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(unknown.bodyAsText().obj().getValue("error").jsonObject.string("code"), wrong.bodyAsText().obj().getValue("error").jsonObject.string("code"))
        assertFalse(wrong.bodyAsText().contains(password))
    }

    @Test fun `replaying a rotated token revokes its whole family including access tokens`() = testApplication {
        application { module(db, auth = AuthService(db, authConfig)) }
        val first = client.login().bodyAsText().obj()
        val second = client.refresh(first.string("refreshToken")).bodyAsText().obj()
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(first.string("refreshToken")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(second.string("refreshToken")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(second.string("accessToken")) }.status)
    }

    @Test fun `mobile logout revokes only its session and missing auth is rejected`() = testApplication {
        application { module(db, auth = AuthService(db, authConfig)) }
        val a = client.login().bodyAsText().obj()
        val b = client.login().bodyAsText().obj()
        val missingAuth = client.get("/api/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, missingAuth.status)
        assertEquals("no-store", missingAuth.headers[HttpHeaders.CacheControl])
        val logout = client.post("/api/v1/auth/mobile/logout") {
            bearerAuth(a.string("accessToken")); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("refreshToken", a.string("refreshToken")) }.toString())
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(a.string("refreshToken")).status)
        assertEquals(HttpStatusCode.OK, client.refresh(b.string("refreshToken")).status)
    }

    @Test fun `web cookie stays HttpOnly secure scoped and requires origin and csrf header`() = testApplication {
        application { module(db, auth = AuthService(db, authConfig)) }
        val login = client.login(web = true)
        assertEquals(HttpStatusCode.OK, login.status)
        val cookie = login.headers.getAll(HttpHeaders.SetCookie)!!.single()
        assertTrue(cookie.contains("HttpOnly", true)); assertTrue(cookie.contains("Secure", true))
        assertTrue(cookie.contains("SameSite=Lax", true)); assertTrue(cookie.contains("Path=/api/v1/auth/web", true))
        assertFalse(login.bodyAsText().contains("refreshToken"))
        val value = cookie.substringBefore(';')
        val missing = client.post("/api/v1/auth/web/refresh") { header(HttpHeaders.Cookie, value) }
        assertEquals(HttpStatusCode.Forbidden, missing.status)
        val evil = client.post("/api/v1/auth/web/refresh") {
            header(HttpHeaders.Cookie, value); header("Origin", "https://evil.example.test"); header("X-TapFlow-CSRF", "1")
        }
        assertEquals(HttpStatusCode.Forbidden, evil.status)
        val refreshed = client.post("/api/v1/auth/web/refresh") {
            header(HttpHeaders.Cookie, value); header("Origin", "https://portal.example.test"); header("X-TapFlow-CSRF", "1")
        }
        assertEquals(HttpStatusCode.OK, refreshed.status)
        val nextCookie = refreshed.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        assertNotEquals(value, nextCookie)
        repeat(2) {
            val out = client.post("/api/v1/auth/web/logout") {
                header(HttpHeaders.Cookie, nextCookie); header("Origin", "https://portal.example.test"); header("X-TapFlow-CSRF", "1")
            }
            assertEquals(HttpStatusCode.NoContent, out.status)
            assertTrue(out.headers[HttpHeaders.SetCookie]!!.contains("Max-Age=0", true))
        }
    }

    @Test fun `malformed credentials and oversized bodies are rejected and registration is off`() = testApplication {
        application { module(db, auth = AuthService(db, authConfig)) }
        assertEquals(HttpStatusCode.BadRequest, client.login("bad-email").status)
        val large = client.post("/api/v1/auth/mobile/login") {
            contentType(ContentType.Application.Json); setBody("x".repeat(20000))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, large.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/auth/demo/customer-register") {
            contentType(ContentType.Application.Json); setBody("""{"email":"new@example.test","password":"valid-test-passphrase"}""")
        }.status)
    }

    @Test fun `concurrent refresh cannot keep a replayed family alive`() = testApplication {
        application { module(db, auth = AuthService(db, authConfig)) }
        val first = client.login().bodyAsText().obj()
        val attempts = kotlinx.coroutines.coroutineScope {
            (1..2).map { async { client.refresh(first.string("refreshToken")) } }.map { it.await() }
        }
        assertEquals(listOf(200, 401), attempts.map { it.status.value }.sorted())
        val rotated = attempts.single { it.status == HttpStatusCode.OK }.bodyAsText().obj()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(rotated.string("accessToken")) }.status)
    }

    @Test fun `active user and current membership are checked instead of stale JWT claims`() = testApplication {
        application { module(db, auth = AuthService(db, authConfig)) }
        val session = client.login().bodyAsText().obj()
        val user = session.getValue("user").jsonObject
        val merchantId = user.getValue("memberships").jsonArray.first().jsonObject.string("merchantId")
        val token = session.string("accessToken")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/merchants/$merchantId") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/merchants/${java.util.UUID.randomUUID()}") { bearerAuth(token) }.status)
        val id = java.util.UUID.fromString(user.string("id"))
        db.query { c -> c.prepareStatement("update users set active=false where id=?").use { it.setObject(1, id); it.executeUpdate() } }
        try {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(session.string("refreshToken")).status)
        } finally {
            db.query { c -> c.prepareStatement("update users set active=true where id=?").use { it.setObject(1, id); it.executeUpdate() } }
        }
    }

    @Test fun `owner guard distinguishes own role from a different tenant and suspended merchant`() = testApplication {
        val access = com.tapflow.api.merchants.MerchantAccess(db)
        application {
            module(db, auth = AuthService(db, authConfig))
            routing {
                authenticate("access") {
                    get("/test/owner/{merchantId}") {
                        val merchant = java.util.UUID.fromString(call.parameters["merchantId"])
                        call.respond(db.query { c -> access.require(c, call.identity().userId, merchant, owner = true) })
                    }
                }
            }
        }
        val owner = client.login().bodyAsText().obj()
        val operator = client.login("operator@example.test").bodyAsText().obj()
        val customer = client.login("customer@example.test").bodyAsText().obj()
        val merchantId = java.util.UUID.fromString(owner.getValue("user").jsonObject.getValue("memberships").jsonArray.first().jsonObject.string("merchantId"))
        val ownerId = java.util.UUID.fromString(owner.getValue("user").jsonObject.string("id"))
        val operatorId = java.util.UUID.fromString(operator.getValue("user").jsonObject.string("id"))
        db.query { c -> assertEquals(merchantId.toString(), access.require(c, ownerId, merchantId, owner = true).merchantId) }
        assertEquals(HttpStatusCode.Forbidden, client.get("/test/owner/$merchantId") { bearerAuth(operator.string("accessToken")) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/test/owner/$merchantId") { bearerAuth(owner.string("accessToken")) }.status)
        val denied = assertThrows(com.tapflow.api.common.DomainError::class.java) {
            db.transaction { c -> access.require(c, operatorId, merchantId, owner = true) }
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertEquals("INSUFFICIENT_ROLE", denied.code)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/merchants/$merchantId") { bearerAuth(customer.string("accessToken")) }.status)
        val other = java.util.UUID.randomUUID()
        db.query { c -> c.prepareStatement("insert into merchants(id,name,slug) values (?,'Other tenant',?)").use { it.setObject(1, other); it.setString(2, other.toString()); it.executeUpdate() } }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/merchants/$other") { bearerAuth(owner.string("accessToken")) }.status)
        db.query { c -> c.prepareStatement("update merchants set status='SUSPENDED' where id=?").use { it.setObject(1, merchantId); it.executeUpdate() } }
        try { assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/merchants/$merchantId") { bearerAuth(owner.string("accessToken")) }.status) }
        finally { db.query { c -> c.prepareStatement("update merchants set status='ACTIVE' where id=?").use { it.setObject(1, merchantId); it.executeUpdate() } } }
    }

    @Test fun `login throttles both account and ip with safe retry metadata`() = testApplication {
        application { module(db, auth = AuthService(db, authConfig)) }
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, client.login(pass = "incorrect-password").status) }
        val limited = client.login()
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue(limited.headers["Retry-After"]!!.toLong() in 1..900)
        assertEquals("RATE_LIMITED", limited.bodyAsText().obj().getValue("error").jsonObject.string("code"))
        repeat(14) { client.login("unknown$it@example.test") }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("last@example.test").status)
    }

    @Test fun `web tokens cannot refresh mobile sessions and foreign logout cannot revoke a victim`() = testApplication {
        application { module(db, auth = AuthService(db, authConfig)) }
        val web = client.login(web = true)
        val webToken = web.headers[HttpHeaders.SetCookie]!!.substringBefore(';').substringAfter('=')
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(webToken).status)
        val owner = client.login().bodyAsText().obj()
        val operator = client.login("operator@example.test").bodyAsText().obj()
        val denied = client.post("/api/v1/auth/mobile/logout") {
            bearerAuth(operator.string("accessToken")); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("refreshToken", owner.string("refreshToken")) }.toString())
        }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
        assertEquals(HttpStatusCode.OK, client.refresh(owner.string("refreshToken")).status)
    }

    @Test fun `enabled registration creates only a customer and no wallet`() = testApplication {
        val enabled = AuthConfig(authConfig.signingSecret, authConfig.issuer, authConfig.audience, authConfig.allowedOrigins, registrationEnabled = true)
        application { module(db, auth = AuthService(db, enabled)) }
        val response = client.post("/api/v1/auth/demo/customer-register") {
            contentType(ContentType.Application.Json); setBody("""{"email":"new-customer@example.test","password":"long-test-only-password"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val duplicate = client.post("/api/v1/auth/demo/customer-register") {
            contentType(ContentType.Application.Json); setBody("""{"email":"new-customer@example.test","password":"long-test-only-password"}""")
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals("ACCOUNT_ALREADY_EXISTS", duplicate.bodyAsText().obj().getValue("error").jsonObject.string("code"))
        val user = response.bodyAsText().obj()
        assertEquals("CUSTOMER", user.string("accountType")); assertTrue(user.getValue("memberships").jsonArray.isEmpty())
        db.query { c -> c.createStatement().use { s -> s.executeQuery("select count(*) from wallets").use { r -> r.next(); assertEquals(0, r.getInt(1)) } } }
    }

    @Test fun `pairing paths are throttled before the pairing feature is enabled`() = testApplication {
        application {
            module(db, auth = AuthService(db, authConfig))
            routing { authenticate("access") { post("/api/v1/terminals/pair") { call.respond(HttpStatusCode.NoContent) } } }
        }
        val session = client.login().bodyAsText().obj()
        repeat(10) { assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/terminals/pair") { bearerAuth(session.string("accessToken")) }.status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.post("/api/v1/terminals/pair") { bearerAuth(session.string("accessToken")) }.status)
    }

    @Test fun `auth rejects non JSON and unknown input fields without a server error`() = testApplication {
        application { module(db, auth = AuthService(db, authConfig)) }
        for ((type, body) in listOf(ContentType.Text.Plain to "not-json", ContentType.Application.Json to "{", ContentType.Application.Json to """{"email":"owner@example.test","password":"$password","role":"OWNER"}""")) {
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/auth/mobile/login") { contentType(type); setBody(body) }.status)
        }
    }

    @Test fun `refresh expiry is absolute and opaque tokens are persisted only as hashes`() = testApplication {
        val clock = MutableClock()
        application { module(db, auth = AuthService(db, authConfig, clock)) }
        val session = client.login().bodyAsText().obj()
        val raw = session.string("refreshToken")
        db.query { c -> c.createStatement().use { s -> s.executeQuery("select token_hash from refresh_sessions").use { r ->
            while (r.next()) { assertEquals(32, r.getBytes(1).size); assertFalse(r.getBytes(1).contentEquals(raw.toByteArray())) }
        } } }
        clock.now = clock.now.plusSeconds(14 * 86400L)
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(raw).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(session.string("accessToken")) }.status)
    }

    @Test fun `mobile logout revokes its authenticated family even if the supplied token is unknown`() = testApplication {
        application { module(db, auth = AuthService(db, authConfig)) }
        val session = client.login().bodyAsText().obj()
        val malformed = client.post("/api/v1/auth/mobile/logout") {
            bearerAuth(session.string("accessToken")); contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"short"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        val logout = client.post("/api/v1/auth/mobile/logout") {
            bearerAuth(session.string("accessToken")); contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("refreshToken", "z".repeat(43)) }.toString())
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(session.string("accessToken")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(session.string("refreshToken")).status)
    }

    @Test fun `mobile login authenticates me and rotates a single use refresh token`() = testApplication {
        application { module(db, auth = AuthService(db, authConfig)) }
        val login = client.post("/api/v1/auth/mobile/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"owner@example.test","password":"$password"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val session = Json.parseToJsonElement(login.bodyAsText()).jsonObject
        val access = session.getValue("accessToken").jsonPrimitive.content
        val refresh = session.getValue("refreshToken").jsonPrimitive.content
        val me = client.get("/api/v1/me") { bearerAuth(access) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("owner@example.test", Json.parseToJsonElement(me.bodyAsText()).jsonObject.getValue("email").jsonPrimitive.content)
        val rotated = client.post("/api/v1/auth/mobile/refresh") {
            contentType(ContentType.Application.Json); setBody("""{"refreshToken":"$refresh"}""")
        }
        assertEquals(HttpStatusCode.OK, rotated.status)
        assertNotEquals(refresh, Json.parseToJsonElement(rotated.bodyAsText()).jsonObject.getValue("refreshToken").jsonPrimitive.content)
    }
}
