package com.tapflow.api.terminals

import com.tapflow.api.auth.AuthService
import com.tapflow.api.config.AuthConfig
import com.tapflow.api.config.DatabaseConfig
import com.tapflow.api.db.Database
import com.tapflow.api.module
import de.mkammerer.argon2.Argon2Factory
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.time.*
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.postgresql.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerminalRoutesTest {
    private val postgres =
        PostgreSQLContainer(
            "postgres@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3"
        )
    private lateinit var db: Database
    private val config =
        AuthConfig(
            "terminal-test-only-secret-over-32-characters",
            "test",
            "clients",
            setOf("https://portal.example.test"),
        )
    private lateinit var hash: String
    private var owner = UUID.randomUUID()
    private var operator = UUID.randomUUID()
    private var merchant = UUID.randomUUID()

    @BeforeAll
    fun start() {
        postgres.start()
        db =
            Database.open(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password, 5))
        hash =
            Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
                .hash(3, 65536, 1, "terminal-test-only-password".toCharArray())
    }

    @AfterAll
    fun stop() {
        db.close()
        postgres.stop()
    }

    @BeforeEach
    fun fixture() {
        owner = UUID.randomUUID()
        operator = UUID.randomUUID()
        merchant = UUID.randomUUID()
        db.transaction { c ->
            for (user in listOf(owner, operator)) c.prepareStatement(
                    "insert into users(id,email,password_hash,account_type) values (?,?,?,'MERCHANT')"
                )
                .use {
                    it.setObject(1, user)
                    it.setString(2, "$user@example.test")
                    it.setString(3, hash)
                    it.executeUpdate()
                }
            c.prepareStatement("insert into merchants(id,name,slug) values (?,'Terminal test',?)")
                .use {
                    it.setObject(1, merchant)
                    it.setString(2, merchant.toString())
                    it.executeUpdate()
                }
            for ((user, role) in listOf(owner to "OWNER", operator to "OPERATOR")) c
                .prepareStatement(
                    "insert into merchant_memberships(user_id,merchant_id,role) values (?,?,?)"
                )
                .use {
                    it.setObject(1, user)
                    it.setObject(2, merchant)
                    it.setString(3, role)
                    it.executeUpdate()
                }
        }
    }

    private suspend fun HttpClient.login(user: UUID): String {
        val r =
            post("/api/v1/auth/mobile/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"email":"$user@example.test","password":"terminal-test-only-password"}"""
                )
            }
        assertEquals(HttpStatusCode.OK, r.status)
        return json(r).getValue("accessToken").jsonPrimitive.content
    }

    private suspend fun HttpClient.code(
        token: String,
        key: UUID = UUID.randomUUID(),
        id: UUID = merchant,
        body: String = "{}",
    ) =
        post("/api/v1/merchants/$id/terminals/pairing-codes") {
            bearerAuth(token)
            header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.pair(
        token: String,
        code: String,
        installation: UUID = UUID.randomUUID(),
        key: UUID = UUID.randomUUID(),
        label: String = "Front counter",
    ) =
        post("/api/v1/terminals/pair") {
            bearerAuth(token)
            header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("pairingCode", code)
                    put("installationId", installation.toString())
                    put("label", label)
                }
                    .toString()
            )
        }

    private suspend fun json(response: HttpResponse) =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject

    @Test
    fun `owner creates a 65 bit ten minute code and retries never store its plaintext`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val token = client.login(owner)
            val key = UUID.randomUUID()
            val first = client.code(token, key)
            assertEquals(HttpStatusCode.Created, first.status)
            val body = json(first)
            val code = body.getValue("pairingCode").jsonPrimitive.content
            assertTrue(code.matches(Regex("[0-9A-HJKMNP-TV-Z]{13}")))
            assertEquals(first.bodyAsText(), client.code(token, key).bodyAsText())
            assertEquals(
                HttpStatusCode.Conflict,
                client.code(token, key, body = """{"labelHint":"Changed"}""").status,
            )
            db.transaction { c ->
                c.prepareStatement(
                        "select code_hash,expires_at-created_at=interval '10 minutes' from terminal_pairing_codes where merchant_id=?"
                    )
                    .use {
                        it.setObject(1, merchant)
                        it.executeQuery().use { r ->
                            assertTrue(r.next())
                            assertEquals(32, r.getBytes(1).size)
                            assertTrue(r.getBoolean(2))
                            assertFalse(r.next())
                        }
                    }
                c.prepareStatement(
                        "select response_json::text from idempotency_records where scope like ?"
                    )
                    .use {
                        it.setString(1, "%$merchant%")
                        it.executeQuery().use { r ->
                            while (r.next()) assertFalse(r.getString(1).contains(code))
                        }
                    }
            }
        }

    @Test
    fun `pairing consumes one code and exact concurrent retries create one terminal`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val ownerToken = client.login(owner)
            val op = client.login(operator)
            val code = json(client.code(ownerToken)).getValue("pairingCode").jsonPrimitive.content
            val installation = UUID.randomUUID()
            val key = UUID.randomUUID()
            val replies =
                kotlinx.coroutines.coroutineScope {
                    (1..6)
                        .map { async { client.pair(op, code, installation, key) } }
                        .map { it.await() }
                }
            assertTrue(replies.all { it.status == HttpStatusCode.Created })
            assertEquals(1, replies.map { it.bodyAsText() }.distinct().size)
            assertEquals(HttpStatusCode.Conflict, client.pair(op, code).status)
            assertEquals(
                HttpStatusCode.Conflict,
                client.pair(op, code, installation, key, label = "Changed").status,
            )
            db.transaction { c ->
                c.prepareStatement("select count(*) from terminals where merchant_id=?").use {
                    it.setObject(1, merchant)
                    it.executeQuery().use { r ->
                        r.next()
                        assertEquals(1, r.getInt(1))
                    }
                }
            }
        }

    @Test
    fun `operator cannot create codes or disable and foreign merchants remain hidden`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val op = client.login(operator)
            val own = client.login(owner)
            assertEquals(HttpStatusCode.Forbidden, client.code(op).status)
            assertEquals(HttpStatusCode.NotFound, client.code(own, id = UUID.randomUUID()).status)
            val code = json(client.code(own)).getValue("pairingCode").jsonPrimitive.content
            val terminal = json(client.pair(op, code)).getValue("terminalId").jsonPrimitive.content
            assertEquals(
                HttpStatusCode.Forbidden,
                client
                    .post("/api/v1/merchants/$merchant/terminals/$terminal/disable") {
                        bearerAuth(op)
                    }
                    .status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client
                    .get("/api/v1/merchants/${UUID.randomUUID()}/terminals") { bearerAuth(own) }
                    .status,
            )
            repeat(2) {
                assertEquals(
                    "DISABLED",
                    json(
                            client.post("/api/v1/merchants/$merchant/terminals/$terminal/disable") {
                                bearerAuth(own)
                            }
                        )
                        .getValue("status")
                        .jsonPrimitive
                        .content,
                )
            }
            val list =
                json(client.get("/api/v1/merchants/$merchant/terminals?limit=1") { bearerAuth(op) })
            assertEquals(
                "DISABLED",
                list
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )
        }

    @Test
    fun `expired malformed or unknown codes cannot pair or create terminals`() = testApplication {
        application {
            module(
                db,
                auth = AuthService(db, config),
                terminals = TerminalService(db, config.signingSecret),
            )
        }
        val token = client.login(owner)
        assertEquals(HttpStatusCode.BadRequest, client.pair(token, "bad").status)
        assertEquals(HttpStatusCode.BadRequest, client.pair(token, "0000000000000").status)
        val code = json(client.code(token)).getValue("pairingCode").jsonPrimitive.content
        db.transaction { c ->
            c.prepareStatement(
                    "update terminal_pairing_codes set created_at=now()-interval '11 minutes',expires_at=now()-interval '1 minute' where merchant_id=?"
                )
                .use {
                    it.setObject(1, merchant)
                    it.executeUpdate()
                }
        }
        assertEquals(HttpStatusCode.Gone, client.pair(token, code).status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client
                .get("/api/v1/merchants/$merchant/terminals?limit=101") { bearerAuth(token) }
                .status,
        )
    }

    @Test
    fun `foreign member cannot burn a known code and fresh requests cannot reactivate a disabled installation`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val own = client.login(owner)
            val op = client.login(operator)
            val raw = json(client.code(own)).getValue("pairingCode").jsonPrimitive.content
            db.transaction { c ->
                c.prepareStatement("delete from merchant_memberships where user_id=?").use {
                    it.setObject(1, operator)
                    it.executeUpdate()
                }
            }
            assertEquals(HttpStatusCode.NotFound, client.pair(op, raw).status)
            val install = UUID.randomUUID()
            val paired = json(client.pair(own, raw, install))
            val terminal = paired.getValue("terminalId").jsonPrimitive.content
            assertEquals(
                HttpStatusCode.OK,
                client
                    .post("/api/v1/merchants/$merchant/terminals/$terminal/disable") {
                        bearerAuth(own)
                    }
                    .status,
            )
            val fresh = json(client.code(own)).getValue("pairingCode").jsonPrimitive.content
            val denied = client.pair(own, fresh, install)
            assertEquals(HttpStatusCode.Conflict, denied.status)
            assertTrue(denied.bodyAsText().contains("TERMINAL_DISABLED"))
            assertEquals(HttpStatusCode.Created, client.pair(own, fresh).status)
        }

    @Test
    fun `terminal keyset pages neither skip nor repeat rows with the same timestamp`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val ids = List(3) { UUID.randomUUID() }
            db.transaction { c ->
                c.prepareStatement(
                        "insert into terminals(id,merchant_id,label,installation_id,paired_by_user_id,paired_at) values (?,?,'Page fixture',?,?,?)"
                    )
                    .use { statement ->
                        for (id in ids) {
                            statement.setObject(1, id)
                            statement.setObject(2, merchant)
                            statement.setObject(3, UUID.randomUUID())
                            statement.setObject(4, owner)
                            statement.setTimestamp(
                                5,
                                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                            )
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
            }
            val token = client.login(operator)
            val seen = mutableListOf<String>()
            var cursor: String? = null
            repeat(3) {
                val response =
                    json(
                        client.get(
                            "/api/v1/merchants/$merchant/terminals?limit=1" +
                                (cursor?.let { "&cursor=$it" } ?: "")
                        ) {
                            bearerAuth(token)
                        }
                    )
                seen +=
                    response
                        .getValue("items")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("terminalId")
                        .jsonPrimitive
                        .content
                cursor =
                    response["nextCursor"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                if (it < 2) org.junit.jupiter.api.Assertions.assertNotNull(cursor)
                else org.junit.jupiter.api.Assertions.assertNull(cursor)
            }
            assertEquals(ids.map { it.toString() }.toSet(), seen.toSet())
            assertEquals(3, seen.distinct().size)
        }

    @Test
    fun `a new code reconciles the same active installation without adding another terminal`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val token = client.login(owner)
            val install = UUID.randomUUID()
            val first =
                json(
                    client.pair(
                        token,
                        json(client.code(token)).getValue("pairingCode").jsonPrimitive.content,
                        install,
                    )
                )
            val second =
                json(
                    client.pair(
                        token,
                        json(client.code(token)).getValue("pairingCode").jsonPrimitive.content,
                        install,
                    )
                )
            assertEquals(first, second)
            assertEquals(
                1,
                json(client.get("/api/v1/merchants/$merchant/terminals") { bearerAuth(token) })
                    .getValue("items")
                    .jsonArray
                    .size,
            )
        }

    @Test
    fun `account terminal cap rejects a new registration without consuming its code`() =
        testApplication {
            db.transaction { c ->
                c.prepareStatement(
                        "insert into terminals(id,merchant_id,label,installation_id,paired_by_user_id) values (?,?,'Limit fixture',?,?)"
                    )
                    .use { s ->
                        repeat(100) {
                            s.setObject(1, UUID.randomUUID())
                            s.setObject(2, merchant)
                            s.setObject(3, UUID.randomUUID())
                            s.setObject(4, owner)
                            s.addBatch()
                        }
                        s.executeBatch()
                    }
            }
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val token = client.login(owner)
            val code = json(client.code(token)).getValue("pairingCode").jsonPrimitive.content
            val response = client.pair(token, code)
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("TERMINAL_LIMIT_REACHED"))
            db.transaction { c ->
                c.prepareStatement(
                        "select consumed_at from terminal_pairing_codes where merchant_id=?"
                    )
                    .use {
                        it.setObject(1, merchant)
                        it.executeQuery().use { r ->
                            assertTrue(r.next())
                            org.junit.jupiter.api.Assertions.assertNull(r.getTimestamp(1))
                        }
                    }
            }
        }

    @Test
    fun `expiry is exclusive at exactly ten minutes`() =
        kotlinx.coroutines.runBlocking {
            val reference =
                java.util.concurrent.atomic.AtomicReference(Instant.parse("2026-11-09T00:00:00Z"))
            val clock =
                object : Clock() {
                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant() = reference.get()
                }
            val service = TerminalService(db, config.signingSecret, clock)
            val identity = com.tapflow.api.auth.Identity(owner, UUID.randomUUID(), "MOBILE")
            val response =
                service.create(
                    identity,
                    merchant,
                    PairingCodeRequest(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                )
            val raw = response.body.getValue("pairingCode").jsonPrimitive.content
            reference.set(reference.get().plusSeconds(600))
            val failure =
                assertThrows(com.tapflow.api.common.DomainError::class.java) {
                    kotlinx.coroutines.runBlocking {
                        service.pair(
                            identity,
                            PairTerminalRequest(raw, UUID.randomUUID().toString(), "Counter"),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                        )
                    }
                }
            assertEquals("PAIRING_CODE_EXPIRED", failure.code)
        }

    @Test
    fun `audit failure rolls back terminal creation consumption and its replay claim`() =
        kotlinx.coroutines.runBlocking {
            val service = TerminalService(db, config.signingSecret)
            val identity = com.tapflow.api.auth.Identity(owner, UUID.randomUUID(), "MOBILE")
            val raw =
                service
                    .create(
                        identity,
                        merchant,
                        PairingCodeRequest(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                    )
                    .body
                    .getValue("pairingCode")
                    .jsonPrimitive
                    .content
            var ids = 0
            val broken =
                TerminalService(
                    db,
                    config.signingSecret,
                    nextId = {
                        if (++ids == 3) error("Injected audit failure") else UUID.randomUUID()
                    },
                )
            val key = UUID.randomUUID()
            val request = PairTerminalRequest(raw, UUID.randomUUID().toString(), "Counter")
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking {
                    broken.pair(identity, request, key, UUID.randomUUID())
                }
            }
            db.transaction { c ->
                c.prepareStatement("select count(*) from terminals where merchant_id=?").use {
                    it.setObject(1, merchant)
                    it.executeQuery().use { r ->
                        r.next()
                        assertEquals(0, r.getInt(1))
                    }
                }
                c.prepareStatement(
                        "select consumed_at from terminal_pairing_codes where merchant_id=?"
                    )
                    .use {
                        it.setObject(1, merchant)
                        it.executeQuery().use { r ->
                            r.next()
                            org.junit.jupiter.api.Assertions.assertNull(r.getTimestamp(1))
                        }
                    }
            }
            assertEquals(201, service.pair(identity, request, key, UUID.randomUUID()).status)
        }

    @Test
    fun `key rotation never replays an incorrect code and already revealed codes still pair`() =
        kotlinx.coroutines.runBlocking {
            val identity = com.tapflow.api.auth.Identity(owner, UUID.randomUUID(), "MOBILE")
            val key = UUID.randomUUID()
            val original =
                TerminalService(db, config.signingSecret)
                    .create(identity, merchant, PairingCodeRequest(), key, UUID.randomUUID())
            val rotated = TerminalService(db, "another-test-only-secret-at-least-32-characters")
            val failure =
                assertThrows(com.tapflow.api.common.DomainError::class.java) {
                    kotlinx.coroutines.runBlocking {
                        rotated.create(
                            identity,
                            merchant,
                            PairingCodeRequest(),
                            key,
                            UUID.randomUUID(),
                        )
                    }
                }
            assertEquals("PAIRING_CODE_UNAVAILABLE", failure.code)
            assertEquals(
                201,
                rotated
                    .pair(
                        identity,
                        PairTerminalRequest(
                            original.body.getValue("pairingCode").jsonPrimitive.content,
                            UUID.randomUUID().toString(),
                            "Counter",
                        ),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                    )
                    .status,
            )
        }

    @Test
    fun `a code expiring during pairing is rechecked before consumption`() =
        kotlinx.coroutines.runBlocking {
            val base = Instant.parse("2026-11-09T00:00:00Z")
            val identity = com.tapflow.api.auth.Identity(owner, UUID.randomUUID(), "MOBILE")
            val raw =
                TerminalService(db, config.signingSecret, Clock.fixed(base, ZoneOffset.UTC))
                    .create(
                        identity,
                        merchant,
                        PairingCodeRequest(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                    )
                    .body
                    .getValue("pairingCode")
                    .jsonPrimitive
                    .content
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val clock =
                object : Clock() {
                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant() =
                        base.plusSeconds(if (reads.incrementAndGet() < 3) 599 else 600)
                }
            val service = TerminalService(db, config.signingSecret, clock)
            val failure =
                assertThrows(com.tapflow.api.common.DomainError::class.java) {
                    kotlinx.coroutines.runBlocking {
                        service.pair(
                            identity,
                            PairTerminalRequest(raw, UUID.randomUUID().toString(), "Counter"),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                        )
                    }
                }
            assertEquals("PAIRING_CODE_EXPIRED", failure.code)
            db.transaction { c ->
                c.prepareStatement("select count(*) from terminals where merchant_id=?").use {
                    it.setObject(1, merchant)
                    it.executeQuery().use { r ->
                        r.next()
                        assertEquals(0, r.getInt(1))
                    }
                }
            }
        }
}
