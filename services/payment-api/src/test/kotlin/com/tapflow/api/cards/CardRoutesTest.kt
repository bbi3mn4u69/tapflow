package com.tapflow.api.cards

import com.tapflow.api.auth.AuthService
import com.tapflow.api.config.AuthConfig
import com.tapflow.api.config.DatabaseConfig
import com.tapflow.api.db.Database
import com.tapflow.api.module
import de.mkammerer.argon2.Argon2Factory
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.postgresql.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CardRoutesTest {
    private val postgres =
        PostgreSQLContainer(
            "postgres@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3"
        )
    private lateinit var db: Database
    private val config =
        AuthConfig(
            "test-only-card-secret-at-least-32-bytes-long",
            "test",
            "clients",
            setOf("https://portal.example.test"),
        )
    private var customer = UUID.randomUUID()
    private val password = "test-only-customer-password"
    private lateinit var hash: String

    @BeforeAll
    fun start() {
        postgres.start()
        db =
            Database.open(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password, 5))
        hash =
            Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
                .hash(3, 65536, 1, password.toCharArray())
    }

    @AfterAll
    fun stop() {
        db.close()
        postgres.stop()
    }

    @BeforeEach
    fun user() {
        customer = UUID.randomUUID()
        addUser(customer)
    }

    private fun addUser(id: UUID, type: String = "CUSTOMER") {
        db.transaction { c ->
            c.prepareStatement(
                    "insert into users(id,email,password_hash,account_type) values (?,?,?,?)"
                )
                .use {
                    it.setObject(1, id)
                    it.setString(2, "$id@example.test")
                    it.setString(3, hash)
                    it.setString(4, type)
                    it.executeUpdate()
                }
        }
    }

    private fun key(curve: String = "secp256r1") =
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec(curve)) }
            .generateKeyPair()

    private fun body(publicKey: ByteArray, label: String = "My demo card") = buildJsonObject {
        put("label", label)
        put("algorithm", "P256_SHA256_ECDSA")
        put("publicKeySpkiBase64", Base64.getEncoder().encodeToString(publicKey))
    }
        .toString()

    private suspend fun io.ktor.client.HttpClient.login(id: UUID = customer): String {
        val response =
            post("/api/v1/auth/mobile/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$id@example.test","password":"$password"}""")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("accessToken")
            .jsonPrimitive
            .content
    }

    private suspend fun io.ktor.client.HttpClient.enroll(
        token: String,
        body: String,
        idempotency: String = UUID.randomUUID().toString(),
    ) =
        post("/api/v1/cards") {
            bearerAuth(token)
            header("Idempotency-Key", idempotency)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `enrollment creates one funded wallet and exact retries replay the stored response`() =
        testApplication {
            application { module(db, auth = AuthService(db, config)) }
            val token = client.login()
            val request = body(key().public.encoded)
            val idempotency = UUID.randomUUID().toString()
            val created = client.enroll(token, request, idempotency)
            assertEquals(HttpStatusCode.Created, created.status)
            val card =
                Json.parseToJsonElement(created.bodyAsText())
                    .jsonObject
                    .getValue("cardId")
                    .jsonPrimitive
                    .content
            val retry = client.enroll(token, request, idempotency)
            assertEquals(HttpStatusCode.Created, retry.status)
            assertEquals(created.bodyAsText(), retry.bodyAsText())
            val wallet = client.get("/api/v1/cards/$card/wallet") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, wallet.status)
            assertEquals(
                10000,
                Json.parseToJsonElement(wallet.bodyAsText())
                    .jsonObject
                    .getValue("balanceMinor")
                    .jsonPrimitive
                    .long,
            )
            db.transaction { c ->
                c.prepareStatement(
                        "select count(*) from wallet_ledger l join wallets w on w.id=l.wallet_id where w.card_id=? and entry_type='DEMO_FUNDING'"
                    )
                    .use {
                        it.setObject(1, UUID.fromString(card))
                        it.executeQuery().use { rows ->
                            rows.next()
                            assertEquals(1, rows.getInt(1))
                        }
                    }
            }
        }

    @Test
    fun `changed idempotent payload conflicts and same key material never funds twice`() =
        testApplication {
            application { module(db, auth = AuthService(db, config)) }
            val token = client.login()
            val spki = key().public.encoded
            val id = UUID.randomUUID().toString()
            val first = client.enroll(token, body(spki), id)
            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(
                HttpStatusCode.Conflict,
                client.enroll(token, body(spki, "Different label"), id).status,
            )
            val again = client.enroll(token, body(spki))
            assertEquals(first.bodyAsText(), again.bodyAsText())
            val listed = client.get("/api/v1/cards") { bearerAuth(token) }
            assertEquals(
                1,
                Json.parseToJsonElement(listed.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .size,
            )
        }

    @Test
    fun `concurrent identical enrollment creates one funding row`() = testApplication {
        application { module(db, auth = AuthService(db, config)) }
        val token = client.login()
        val request = body(key().public.encoded)
        val id = UUID.randomUUID().toString()
        val replies =
            kotlinx.coroutines.coroutineScope {
                (1..12).map { async { client.enroll(token, request, id) } }.map { it.await() }
            }
        assertTrue(replies.all { it.status == HttpStatusCode.Created })
        assertEquals(1, replies.map { it.bodyAsText() }.distinct().size)
        db.transaction { c ->
            c.prepareStatement(
                    "select count(*) from wallet_ledger l join wallets w on w.id=l.wallet_id join virtual_cards v on v.id=w.card_id where v.owner_user_id=?"
                )
                .use {
                    it.setObject(1, customer)
                    it.executeQuery().use { r ->
                        r.next()
                        assertEquals(1, r.getInt(1))
                    }
                }
        }
    }

    @Test
    fun `role and ownership checks hide another customers card wallet and activity`() =
        testApplication {
            application { module(db, auth = AuthService(db, config)) }
            val owner = client.login()
            val spki = key().public.encoded
            val card =
                Json.parseToJsonElement(client.enroll(owner, body(spki)).bodyAsText())
                    .jsonObject
                    .getValue("cardId")
                    .jsonPrimitive
                    .content
            val other = UUID.randomUUID()
            addUser(other)
            val token = client.login(other)
            for (suffix in listOf("", "/wallet", "/activity")) assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/v1/cards/$card$suffix") { bearerAuth(token) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.post("/api/v1/cards/$card/disable") { bearerAuth(token) }.status,
            )
            assertEquals(HttpStatusCode.Conflict, client.enroll(token, body(spki)).status)
            val merchant = UUID.randomUUID()
            addUser(merchant, "MERCHANT")
            val merchantToken = client.login(merchant)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/v1/cards") { bearerAuth(merchantToken) }.status,
            )
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/cards").status)
        }

    @Test
    fun `disable is repeatable and never changes funding or exposes private keys`() =
        testApplication {
            application { module(db, auth = AuthService(db, config)) }
            val token = client.login()
            val card =
                Json.parseToJsonElement(
                        client.enroll(token, body(key().public.encoded)).bodyAsText()
                    )
                    .jsonObject
                    .getValue("cardId")
                    .jsonPrimitive
                    .content
            repeat(2) {
                val response = client.post("/api/v1/cards/$card/disable") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(
                    "BLOCKED",
                    Json.parseToJsonElement(response.bodyAsText())
                        .jsonObject
                        .getValue("status")
                        .jsonPrimitive
                        .content,
                )
            }
            val ledger = client.get("/api/v1/cards/$card/activity?limit=1") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, ledger.status)
            val page = Json.parseToJsonElement(ledger.bodyAsText()).jsonObject
            assertEquals(
                "DEMO_FUNDING",
                page
                    .getValue("items")
                    .jsonArray
                    .first()
                    .jsonObject
                    .getValue("entryType")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(JsonNull, page["nextCursor"])
            assertFalse(ledger.bodyAsText().contains("privateKey", true))
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/v1/cards/$card/activity?limit=101") { bearerAuth(token) }.status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/v1/cards/$card/activity?cursor=bad") { bearerAuth(token) }.status,
            )
        }

    @Test
    fun `invalid key encodings curves labels and idempotency headers fail before writes`() =
        testApplication {
            application { module(db, auth = AuthService(db, config)) }
            val token = client.login()
            for (request in
                listOf(
                    body(byteArrayOf()),
                    body(key("secp384r1").public.encoded),
                    body(key().public.encoded, " "),
                    body(key().public.encoded, "x".repeat(81)),
                )) {
                assertEquals(HttpStatusCode.BadRequest, client.enroll(token, request).status)
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.enroll(token, body(key().public.encoded), "bad-key").status,
            )
            val listed = client.get("/api/v1/cards") { bearerAuth(token) }
            assertTrue(
                Json.parseToJsonElement(listed.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .isEmpty()
            )
        }

    @Test
    fun `enrollment limits fresh keys but retries still reconcile without funding again`() =
        testApplication {
            application { module(db, auth = AuthService(db, config)) }
            val token = client.login()
            val first = body(key().public.encoded)
            val id = UUID.randomUUID().toString()
            repeat(3) { index ->
                assertEquals(
                    HttpStatusCode.Created,
                    client
                        .enroll(
                            token,
                            if (index == 0) first else body(key().public.encoded),
                            if (index == 0) id else UUID.randomUUID().toString(),
                        )
                        .status,
                )
            }
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.enroll(token, body(key().public.encoded)).status,
            )
            assertEquals(HttpStatusCode.Created, client.enroll(token, first, id).status)
        }

    @Test
    fun `disabled enrollment prevents funding but authenticated card listing still works`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    cardConfig = com.tapflow.api.config.CardConfig(enrollmentEnabled = false),
                )
            }
            val token = client.login()
            assertEquals(
                HttpStatusCode.Forbidden,
                client.enroll(token, body(key().public.encoded)).status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/cards") { bearerAuth(token) }.status,
            )
        }

    @Test
    fun `funding failure rolls back the card wallet ledger and idempotency claim`() =
        kotlinx.coroutines.runBlocking {
            var calls = 0
            val service =
                CardService(
                    db,
                    com.tapflow.api.config.CardConfig(),
                    nextId = {
                        calls++
                        if (calls == 5) error("Injected audit failure")
                        UUID.randomUUID()
                    },
                )
            val request =
                EnrollmentRequest(
                    "Rollback test",
                    "P256_SHA256_ECDSA",
                    Base64.getEncoder().encodeToString(key().public.encoded),
                )
            val identity = com.tapflow.api.auth.Identity(customer, UUID.randomUUID(), "MOBILE")
            val idempotency = UUID.randomUUID()
            assertThrows<IllegalStateException> {
                kotlinx.coroutines.runBlocking {
                    service.enroll(identity, request, idempotency, UUID.randomUUID())
                }
            }
            assertEquals(5, calls)
            db.transaction { c ->
                c.prepareStatement("select count(*) from virtual_cards where owner_user_id=?").use {
                    it.setObject(1, customer)
                    it.executeQuery().use { r ->
                        r.next()
                        assertEquals(0, r.getInt(1))
                    }
                }
                c.prepareStatement("select count(*) from idempotency_records where scope=?").use {
                    it.setString(1, "cards:enroll:$customer")
                    it.executeQuery().use { r ->
                        r.next()
                        assertEquals(0, r.getInt(1))
                    }
                }
            }
            assertEquals(
                201,
                CardService(db, com.tapflow.api.config.CardConfig())
                    .enroll(identity, request, idempotency, UUID.randomUUID())
                    .status,
            )
        }

    @Test
    fun `activity rejects out of range and noncanonical cursor timestamps`() = testApplication {
        application { module(db, auth = AuthService(db, config)) }
        val token = client.login()
        val card =
            Json.parseToJsonElement(client.enroll(token, body(key().public.encoded)).bodyAsText())
                .jsonObject
                .getValue("cardId")
                .jsonPrimitive
                .content
        for (time in
            listOf(
                "+1000000000-12-31T23:59:59Z",
                "1969-01-01T00:00:00Z",
                "2026-01-01T00:00:00.000000001Z",
            )) {
            val cursor =
                Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("$card|$time|${UUID.randomUUID()}".toByteArray())
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .get("/api/v1/cards/$card/activity?cursor=$cursor") { bearerAuth(token) }
                    .status,
            )
        }
    }

    @Test
    fun `account card cap bounds listing and prevents additional funding`() = testApplication {
        db.transaction { c ->
            c.prepareStatement(
                    "insert into virtual_cards(id,owner_user_id,label,public_key_spki,created_at) values (?,?,'Limit fixture',?,now()-interval '2 days')"
                )
                .use {
                    repeat(100) { _ ->
                        it.setObject(1, UUID.randomUUID())
                        it.setObject(2, customer)
                        it.setBytes(3, key().public.encoded)
                        it.addBatch()
                    }
                    it.executeBatch()
                }
        }
        application { module(db, auth = AuthService(db, config)) }
        val token = client.login()
        assertEquals(
            100,
            Json.parseToJsonElement(client.get("/api/v1/cards") { bearerAuth(token) }.bodyAsText())
                .jsonObject
                .getValue("items")
                .jsonArray
                .size,
        )
        val denied = client.enroll(token, body(key().public.encoded))
        assertEquals(HttpStatusCode.Conflict, denied.status)
        assertTrue(denied.bodyAsText().contains("CARD_LIMIT_REACHED"))
    }
}
