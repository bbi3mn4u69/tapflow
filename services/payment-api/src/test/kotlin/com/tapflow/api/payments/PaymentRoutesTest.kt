package com.tapflow.api.payments

import com.tapflow.api.auth.AuthService
import com.tapflow.api.config.*
import com.tapflow.api.db.Database
import com.tapflow.api.module
import com.tapflow.api.terminals.TerminalService
import com.tapflow.protocol.*
import de.mkammerer.argon2.Argon2Factory
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.*
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.postgresql.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentRoutesTest {
    private val postgres =
        PostgreSQLContainer(
            "postgres@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3"
        )
    private lateinit var db: Database
    private lateinit var hash: String
    private val config =
        AuthConfig(
            "payments-test-only-secret-at-least-32-characters",
            "test",
            "clients",
            setOf("https://portal.example.test"),
        )
    private var merchant = UUID.randomUUID()
    private var operator = UUID.randomUUID()
    private var customer = UUID.randomUUID()
    private var terminal = UUID.randomUUID()
    private var card = UUID.randomUUID()
    private var wallet = UUID.randomUUID()
    private lateinit var key: KeyPair

    @BeforeAll
    fun start() {
        postgres.start()
        db =
            Database.open(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password, 8))
        hash =
            Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
                .hash(3, 65536, 1, "payment-test-only-password".toCharArray())
    }

    @AfterAll
    fun stop() {
        db.close()
        postgres.stop()
    }

    @BeforeEach
    fun fixture() {
        merchant = UUID.randomUUID()
        operator = UUID.randomUUID()
        customer = UUID.randomUUID()
        terminal = UUID.randomUUID()
        card = UUID.randomUUID()
        wallet = UUID.randomUUID()
        key =
            KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        db.transaction { c ->
            for ((user, type) in listOf(operator to "MERCHANT", customer to "CUSTOMER")) c
                .prepareStatement(
                    "insert into users(id,email,password_hash,account_type) values (?,?,?,?)"
                )
                .use {
                    it.setObject(1, user)
                    it.setString(2, "$user@example.test")
                    it.setString(3, hash)
                    it.setString(4, type)
                    it.executeUpdate()
                }
            c.prepareStatement("insert into merchants(id,name,slug) values (?,'Payment fixture',?)")
                .use {
                    it.setObject(1, merchant)
                    it.setString(2, merchant.toString())
                    it.executeUpdate()
                }
            c.prepareStatement(
                    "insert into merchant_memberships(user_id,merchant_id,role) values (?,?,'OPERATOR')"
                )
                .use {
                    it.setObject(1, operator)
                    it.setObject(2, merchant)
                    it.executeUpdate()
                }
            c.prepareStatement(
                    "insert into terminals(id,merchant_id,label,installation_id,paired_by_user_id) values (?,?,'Test counter',?,?)"
                )
                .use {
                    it.setObject(1, terminal)
                    it.setObject(2, merchant)
                    it.setObject(3, UUID.randomUUID())
                    it.setObject(4, operator)
                    it.executeUpdate()
                }
            c.prepareStatement(
                    "insert into virtual_cards(id,owner_user_id,label,public_key_spki) values (?,?,'Test card',?)"
                )
                .use {
                    it.setObject(1, card)
                    it.setObject(2, customer)
                    it.setBytes(3, key.public.encoded)
                    it.executeUpdate()
                }
            c.prepareStatement("insert into wallets(id,card_id,balance_minor) values (?,?,10000)")
                .use {
                    it.setObject(1, wallet)
                    it.setObject(2, card)
                    it.executeUpdate()
                }
            c.prepareStatement(
                    "insert into wallet_ledger(id,wallet_id,entry_type,amount_minor,balance_after_minor) values (?,?,'DEMO_FUNDING',10000,10000)"
                )
                .use {
                    it.setObject(1, UUID.randomUUID())
                    it.setObject(2, wallet)
                    it.executeUpdate()
                }
        }
    }

    private suspend fun HttpClient.login(user: UUID = operator): String {
        val response =
            post("/api/v1/auth/mobile/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"email":"$user@example.test","password":"payment-test-only-password"}"""
                )
            }
        assertEquals(HttpStatusCode.OK, response.status)
        return json(response).getValue("accessToken").jsonPrimitive.content
    }

    private suspend fun json(r: HttpResponse) = Json.parseToJsonElement(r.bodyAsText()).jsonObject

    private suspend fun HttpClient.create(
        token: String,
        amount: Long = 2500,
        idempotency: UUID = UUID.randomUUID(),
        terminalId: UUID = terminal,
        merchantId: UUID = merchant,
    ) =
        post("/api/v1/merchants/$merchantId/payment-intents") {
            bearerAuth(token)
            header("Idempotency-Key", idempotency)
            contentType(ContentType.Application.Json)
            setBody("""{"terminalId":"$terminalId","amountMinor":$amount,"currency":"AUD"}""")
        }

    private fun proof(
        intent: JsonObject,
        signer: KeyPair = key,
        cardId: UUID = card,
        amount: Long = intent.getValue("amountMinor").jsonPrimitive.long,
    ): String {
        val challenge = intent.getValue("challenge").jsonObject
        val value =
            Challenge(
                UUID.fromString(intent.getValue("id").jsonPrimitive.content),
                UUID.fromString(intent.getValue("merchantId").jsonPrimitive.content),
                amount,
                Base64.getDecoder().decode(challenge.getValue("nonceBase64").jsonPrimitive.content),
                challenge.getValue("expiresAtEpochMillis").jsonPrimitive.long,
            )
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(signer.private)
                update(ChallengeCodec.canonical(value, cardId))
                sign()
            }
        return buildJsonObject {
                put("terminalId", intent.getValue("terminalId"))
                put("cardId", cardId.toString())
                put("signatureDerBase64", Base64.getEncoder().encodeToString(signature))
            }
            .toString()
    }

    private suspend fun HttpClient.authorise(
        token: String,
        id: String,
        proof: String,
        idempotency: UUID = UUID.randomUUID(),
        merchantId: UUID = merchant,
    ) =
        post("/api/v1/merchants/$merchantId/payment-intents/$id/authorise") {
            bearerAuth(token)
            header("Idempotency-Key", idempotency)
            contentType(ContentType.Application.Json)
            setBody(proof)
        }

    private fun balance(): Long = db.transaction { c ->
        c.prepareStatement("select balance_minor from wallets where id=?").use {
            it.setObject(1, wallet)
            it.executeQuery().use { r ->
                r.next()
                r.getLong(1)
            }
        }
    }

    private fun debits(): Int = db.transaction { c ->
        c.prepareStatement(
                "select count(*) from wallet_ledger where wallet_id=? and entry_type='DEBIT'"
            )
            .use {
                it.setObject(1, wallet)
                it.executeQuery().use { r ->
                    r.next()
                    r.getInt(1)
                }
            }
    }

    @Test
    fun `a 25 dollar authorisation debits 100 to 75 and creates one immutable receipt`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val token = client.login()
            val created = client.create(token)
            assertEquals(HttpStatusCode.Created, created.status)
            val intent = json(created)
            val id = intent.getValue("id").jsonPrimitive.content
            val response = client.authorise(token, id, proof(intent))
            assertEquals(HttpStatusCode.OK, response.status)
            val decision = json(response)
            assertEquals("APPROVED", decision.getValue("status").jsonPrimitive.content)
            assertTrue(decision.getValue("receiptNumber").jsonPrimitive.content.startsWith("TF-"))
            assertEquals(7500, balance())
            assertEquals(1, debits())
            val status =
                json(
                    client.get("/api/v1/merchants/$merchant/payment-intents/$id") {
                        bearerAuth(token)
                    }
                )
            assertEquals("APPROVED", status.getValue("status").jsonPrimitive.content)
            val detail =
                json(client.get("/api/v1/merchants/$merchant/payments/$id") { bearerAuth(token) })
            assertEquals(decision.getValue("receiptNumber"), detail.getValue("receiptNumber"))
            assertEquals("Test card", detail.getValue("cardAlias").jsonPrimitive.content)
        }

    @Test
    fun `twenty identical concurrent submissions return the same response and debit once`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val token = client.login()
            val intent = json(client.create(token))
            val id = intent.getValue("id").jsonPrimitive.content
            val request = proof(intent)
            val key = UUID.randomUUID()
            val responses =
                kotlinx.coroutines.coroutineScope {
                    (1..20)
                        .map { async { client.authorise(token, id, request, key) } }
                        .map { it.await() }
                }
            assertTrue(responses.all { it.status == HttpStatusCode.OK })
            assertEquals(1, responses.map { it.bodyAsText() }.distinct().size)
            assertEquals(7500, balance())
            assertEquals(1, debits())
            assertEquals(HttpStatusCode.OK, client.authorise(token, id, request).status)
            assertEquals(
                HttpStatusCode.Conflict,
                client.authorise(token, id, proof(intent), key).status,
            )
            assertEquals(HttpStatusCode.Conflict, client.authorise(token, id, proof(intent)).status)
        }

    @Test
    fun `invalid signature and tampered amount never change wallet or decide an intent`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val token = client.login()
            val intent = json(client.create(token))
            val id = intent.getValue("id").jsonPrimitive.content
            for (request in
                listOf(
                    proof(intent, amount = 2501),
                    proof(
                        intent,
                        signer =
                            KeyPairGenerator.getInstance("EC")
                                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                                .generateKeyPair(),
                    ),
                )) {
                val result = client.authorise(token, id, request)
                assertEquals(HttpStatusCode.BadRequest, result.status)
                assertTrue(result.bodyAsText().contains("INVALID_CARD_PROOF"))
            }
            assertEquals(10000, balance())
            assertEquals(0, debits())
            assertEquals(
                "AWAITING_TAP",
                json(
                        client.get("/api/v1/merchants/$merchant/payment-intents/$id") {
                            bearerAuth(token)
                        }
                    )
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )
        }

    @Test
    fun `blocked cards and insufficient funds are committed declines with no debit`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val token = client.login()
            val first = json(client.create(token, 10001))
            val id = first.getValue("id").jsonPrimitive.content
            val declined = json(client.authorise(token, id, proof(first)))
            assertEquals("DECLINED", declined.getValue("status").jsonPrimitive.content)
            assertEquals(
                "INSUFFICIENT_FUNDS",
                declined.getValue("decisionCode").jsonPrimitive.content,
            )
            db.transaction { c ->
                c.prepareStatement("update virtual_cards set status='BLOCKED' where id=?").use {
                    it.setObject(1, card)
                    it.executeUpdate()
                }
            }
            val second = json(client.create(token))
            val blocked =
                json(
                    client.authorise(
                        token,
                        second.getValue("id").jsonPrimitive.content,
                        proof(second),
                    )
                )
            assertEquals("CARD_BLOCKED", blocked.getValue("decisionCode").jsonPrimitive.content)
            assertEquals(10000, balance())
            assertEquals(0, debits())
        }

    @Test
    fun `creation retries preserve nonce and expiry and a changed request conflicts`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val token = client.login()
            val key = UUID.randomUUID()
            val first = client.create(token, idempotency = key)
            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(first.bodyAsText(), client.create(token, idempotency = key).bodyAsText())
            assertEquals(HttpStatusCode.Conflict, client.create(token, 2501, key).status)
            val intent = json(first)
            val expiry = Instant.parse(intent.getValue("expiresAt").jsonPrimitive.content)
            assertEquals(
                90,
                java.time.Duration.between(
                        Instant.parse(intent.getValue("createdAt").jsonPrimitive.content),
                        expiry,
                    )
                    .seconds,
            )
            assertEquals(
                expiry.toEpochMilli(),
                intent
                    .getValue("challenge")
                    .jsonObject
                    .getValue("expiresAtEpochMillis")
                    .jsonPrimitive
                    .long,
            )
            assertEquals(
                16,
                Base64.getDecoder()
                    .decode(
                        intent
                            .getValue("challenge")
                            .jsonObject
                            .getValue("nonceBase64")
                            .jsonPrimitive
                            .content
                    )
                    .size,
            )
        }

    @Test
    fun `cancel is repeatable expiry is persisted and neither permits later authorisation`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val token = client.login()
            val first = json(client.create(token))
            val id = first.getValue("id").jsonPrimitive.content
            repeat(2) {
                assertEquals(
                    "CANCELLED",
                    json(
                            client.post("/api/v1/merchants/$merchant/payment-intents/$id/cancel") {
                                bearerAuth(token)
                            }
                        )
                        .getValue("status")
                        .jsonPrimitive
                        .content,
                )
            }
            assertEquals(HttpStatusCode.Conflict, client.authorise(token, id, proof(first)).status)
            val second = json(client.create(token))
            val next = second.getValue("id").jsonPrimitive.content
            db.transaction { c ->
                c.prepareStatement(
                        "update payment_intents set created_at=now()-interval '100 seconds',expires_at=now()-interval '10 seconds' where id=?"
                    )
                    .use {
                        it.setObject(1, UUID.fromString(next))
                        it.executeUpdate()
                    }
            }
            assertEquals(HttpStatusCode.Gone, client.authorise(token, next, proof(second)).status)
            assertEquals(
                "EXPIRED",
                json(
                        client.get("/api/v1/merchants/$merchant/payment-intents/$next") {
                            bearerAuth(token)
                        }
                    )
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(10000, balance())
            assertEquals(0, debits())
        }

    @Test
    fun `foreign tenant terminal and customer attempts cannot create or read merchant payments`() =
        testApplication {
            application {
                module(
                    db,
                    auth = AuthService(db, config),
                    terminals = TerminalService(db, config.signingSecret),
                )
            }
            val token = client.login()
            val intent = json(client.create(token))
            val id = intent.getValue("id").jsonPrimitive.content
            val foreign = UUID.randomUUID()
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/v1/merchants/$foreign/payments/$id") { bearerAuth(token) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.authorise(token, id, proof(intent), merchantId = foreign).status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.create(token, terminalId = UUID.randomUUID()).status,
            )
            assertEquals(HttpStatusCode.NotFound, client.create(client.login(customer)).status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/merchants/$merchant/payments").status,
            )
        }

    private fun anotherTerminal(): UUID =
        UUID.randomUUID().also { id ->
            db.transaction { c ->
                c.prepareStatement(
                        "insert into terminals(id,merchant_id,label,installation_id,paired_by_user_id) values (?,?,'Second counter',?,?)"
                    )
                    .use {
                        it.setObject(1, id)
                        it.setObject(2, merchant)
                        it.setObject(3, UUID.randomUUID())
                        it.setObject(4, operator)
                        it.executeUpdate()
                    }
            }
        }

    @Test
    fun `concurrent different payments cannot overdraw the same wallet`() = testApplication {
        application { module(db, auth = AuthService(db, config)) }
        val token = client.login()
        val first = json(client.create(token, 8000))
        val second = json(client.create(token, 8000, terminalId = anotherTerminal()))
        val responses =
            kotlinx.coroutines.coroutineScope {
                listOf(first, second)
                    .map { intent ->
                        async {
                            json(
                                client.authorise(
                                    token,
                                    intent.getValue("id").jsonPrimitive.content,
                                    proof(intent),
                                )
                            )
                        }
                    }
                    .map { it.await() }
            }
        assertEquals(
            setOf("APPROVED", "DECLINED"),
            responses.map { it.getValue("status").jsonPrimitive.content }.toSet(),
        )
        assertEquals(2000, balance())
        assertEquals(1, debits())
    }

    @Test
    fun `new idempotency keys cannot submit the same decided payment twice`() = testApplication {
        application { module(db, auth = AuthService(db, config)) }
        val token = client.login()
        val intent = json(client.create(token))
        val id = intent.getValue("id").jsonPrimitive.content
        val request = proof(intent)
        val responses =
            kotlinx.coroutines.coroutineScope {
                (1..20).map { async { client.authorise(token, id, request) } }.map { it.await() }
            }
        assertTrue(responses.all { it.status == HttpStatusCode.OK })
        assertEquals(1, responses.map { it.bodyAsText() }.distinct().size)
        assertEquals(7500, balance())
        assertEquals(1, debits())
    }

    @Test
    fun `proofs cannot move between intents or change nonce expiry or merchant`() =
        testApplication {
            application { module(db, auth = AuthService(db, config)) }
            val token = client.login()
            val intent = json(client.create(token))
            val other = json(client.create(token, terminalId = anotherTerminal()))
            val replay =
                Json.parseToJsonElement(proof(intent)).jsonObject.toMutableMap().apply {
                    put("terminalId", other.getValue("terminalId"))
                }
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .authorise(
                        token,
                        other.getValue("id").jsonPrimitive.content,
                        JsonObject(replay).toString(),
                    )
                    .status,
            )
            val challenge = intent.getValue("challenge").jsonObject
            val nonce =
                Base64.getDecoder()
                    .decode(challenge.getValue("nonceBase64").jsonPrimitive.content)
                    .also { it[0] = (it[0].toInt() xor 1).toByte() }
            val tampered =
                listOf(
                    JsonObject(
                        intent + ("merchantId" to JsonPrimitive(UUID.randomUUID().toString()))
                    ),
                    JsonObject(
                        intent +
                            ("challenge" to
                                JsonObject(
                                    challenge +
                                        ("nonceBase64" to
                                            JsonPrimitive(
                                                Base64.getEncoder().encodeToString(nonce)
                                            ))
                                ))
                    ),
                    JsonObject(
                        intent +
                            ("challenge" to
                                JsonObject(
                                    challenge +
                                        ("expiresAtEpochMillis" to
                                            JsonPrimitive(
                                                challenge
                                                    .getValue("expiresAtEpochMillis")
                                                    .jsonPrimitive
                                                    .long + 1
                                            ))
                                ))
                    ),
                )
            for (value in tampered) assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .authorise(token, intent.getValue("id").jsonPrimitive.content, proof(value))
                    .status,
            )
            assertEquals(10000, balance())
            assertEquals(0, debits())
        }

    @Test
    fun `disabled terminal does not rewrite a committed receipt`() = testApplication {
        application { module(db, auth = AuthService(db, config)) }
        val token = client.login()
        val intent = json(client.create(token))
        val id = intent.getValue("id").jsonPrimitive.content
        val request = proof(intent)
        val key = UUID.randomUUID()
        val approved = client.authorise(token, id, request, key)
        assertEquals(HttpStatusCode.OK, approved.status)
        db.transaction { c ->
            c.prepareStatement("update terminals set status='DISABLED' where id=?").use {
                it.setObject(1, terminal)
                it.executeUpdate()
            }
        }
        assertEquals(HttpStatusCode.Conflict, client.create(token).status)
        assertEquals(approved.bodyAsText(), client.authorise(token, id, request, key).bodyAsText())
        assertEquals(approved.bodyAsText(), client.authorise(token, id, request).bodyAsText())
        assertEquals(7500, balance())
        assertEquals(1, debits())
    }

    @Test
    fun `cancel and authorise race leaves exactly one consistent terminal outcome`() =
        testApplication {
            application { module(db, auth = AuthService(db, config)) }
            val token = client.login()
            val intent = json(client.create(token))
            val id = intent.getValue("id").jsonPrimitive.content
            val responses =
                kotlinx.coroutines.coroutineScope {
                    val a = async { client.authorise(token, id, proof(intent)) }
                    val b = async {
                        client.post("/api/v1/merchants/$merchant/payment-intents/$id/cancel") {
                            bearerAuth(token)
                        }
                    }
                    listOf(a.await(), b.await())
                }
            assertEquals(
                setOf(HttpStatusCode.OK, HttpStatusCode.Conflict),
                responses.map { it.status }.toSet(),
            )
            val status =
                json(
                        client.get("/api/v1/merchants/$merchant/payment-intents/$id") {
                            bearerAuth(token)
                        }
                    )
                    .getValue("status")
                    .jsonPrimitive
                    .content
            assertTrue(status in setOf("APPROVED", "CANCELLED"))
            assertEquals(if (status == "APPROVED") 7500L else 10000L, balance())
            assertEquals(if (status == "APPROVED") 1 else 0, debits())
        }

    @Test
    fun `payment list keyset pages and filters stay inside their merchant`() = testApplication {
        application { module(db, auth = AuthService(db, config)) }
        val token = client.login()
        val first = json(client.create(token))
        val id = first.getValue("id").jsonPrimitive.content
        client.authorise(token, id, proof(first))
        val second = json(client.create(token))
        client.post(
            "/api/v1/merchants/$merchant/payment-intents/${second.getValue("id").jsonPrimitive.content}/cancel"
        ) {
            bearerAuth(token)
        }
        val page =
            json(client.get("/api/v1/merchants/$merchant/payments?limit=1") { bearerAuth(token) })
        val cursor = page.getValue("nextCursor").jsonPrimitive.content
        val next =
            json(
                client.get("/api/v1/merchants/$merchant/payments?limit=1&cursor=$cursor") {
                    bearerAuth(token)
                }
            )
        assertNotEquals(
            page.getValue("items").jsonArray.single().jsonObject.getValue("paymentId"),
            next.getValue("items").jsonArray.single().jsonObject.getValue("paymentId"),
        )
        assertEquals(JsonNull, next["nextCursor"])
        val approved =
            json(
                    client.get("/api/v1/merchants/$merchant/payments?status=APPROVED") {
                        bearerAuth(token)
                    }
                )
                .getValue("items")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(2500, approved.getValue("netAmountMinor").jsonPrimitive.long)
        assertEquals(
            HttpStatusCode.BadRequest,
            client
                .get("/api/v1/merchants/$merchant/payments?limit=101") { bearerAuth(token) }
                .status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client
                .get(
                    "/api/v1/merchants/$merchant/payments?from=2026-01-01T00:00:00Z&to=2025-01-01T00:00:00Z"
                ) {
                    bearerAuth(token)
                }
                .status,
        )
    }

    @Test
    fun `audit failure rolls back debit ledger decision and authorisation claim`() =
        kotlinx.coroutines.runBlocking {
            val identity = com.tapflow.api.auth.Identity(operator, UUID.randomUUID(), "MOBILE")
            val service = PaymentService(db)
            val intent =
                service
                    .create(
                        identity,
                        merchant,
                        CreateIntentRequest(terminal.toString(), 2500, "AUD"),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                    )
                    .body
            val request = Json.decodeFromString<AuthoriseRequest>(proof(intent))
            val id = UUID.fromString(intent.getValue("id").jsonPrimitive.content)
            val key = UUID.randomUUID()
            var calls = 0
            val broken =
                PaymentService(
                    db,
                    nextId = {
                        if (++calls == 3) error("Injected audit failure") else UUID.randomUUID()
                    },
                )
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking {
                    broken.authorise(identity, merchant, id, request, key, UUID.randomUUID())
                }
            }
            assertEquals(10000, balance())
            assertEquals(0, debits())
            assertEquals(
                "AWAITING_TAP",
                service.get(identity, merchant, id, UUID.randomUUID()).status,
            )
            assertEquals(
                200,
                service.authorise(identity, merchant, id, request, key, UUID.randomUUID()).status,
            )
            assertEquals(7500, balance())
            assertEquals(1, debits())
        }

    @Test
    fun `expiry is rechecked after proof verification and wallet locking`() =
        kotlinx.coroutines.runBlocking {
            val base = Instant.parse("2026-11-09T00:00:00Z")
            val identity = com.tapflow.api.auth.Identity(operator, UUID.randomUUID(), "MOBILE")
            val intent =
                PaymentService(db, java.time.Clock.fixed(base, java.time.ZoneOffset.UTC))
                    .create(
                        identity,
                        merchant,
                        CreateIntentRequest(terminal.toString(), 2500, "AUD"),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                    )
                    .body
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val clock =
                object : java.time.Clock() {
                    override fun getZone() = java.time.ZoneOffset.UTC

                    override fun withZone(zone: java.time.ZoneId): java.time.Clock = this

                    override fun instant() =
                        base.plusSeconds(if (reads.incrementAndGet() < 3) 89 else 90)
                }
            val response =
                PaymentService(db, clock)
                    .authorise(
                        identity,
                        merchant,
                        UUID.fromString(intent.getValue("id").jsonPrimitive.content),
                        Json.decodeFromString<AuthoriseRequest>(proof(intent)),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                    )
            assertEquals(410, response.status)
            assertEquals(10000, balance())
            assertEquals(0, debits())
        }

    @Test
    fun `disabled pending terminal rejects proof but permits status and cancellation`() =
        testApplication {
            application { module(db, auth = AuthService(db, config)) }
            val token = client.login()
            val intent = json(client.create(token))
            val id = intent.getValue("id").jsonPrimitive.content
            db.transaction { c ->
                c.prepareStatement("update terminals set status='DISABLED' where id=?").use {
                    it.setObject(1, terminal)
                    it.executeUpdate()
                }
            }
            assertEquals(HttpStatusCode.Conflict, client.authorise(token, id, proof(intent)).status)
            assertEquals(
                "AWAITING_TAP",
                json(
                        client.get("/api/v1/merchants/$merchant/payment-intents/$id") {
                            bearerAuth(token)
                        }
                    )
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                "CANCELLED",
                json(
                        client.post("/api/v1/merchants/$merchant/payment-intents/$id/cancel") {
                            bearerAuth(token)
                        }
                    )
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(10000, balance())
            assertEquals(0, debits())
        }

    @Test
    fun `concurrent fresh creation keys cannot open two pending intents on one terminal`() =
        testApplication {
            application { module(db, auth = AuthService(db, config)) }
            val token = client.login()
            val responses =
                kotlinx.coroutines.coroutineScope {
                    (1..10).map { async { client.create(token) } }.map { it.await() }
                }
            assertEquals(1, responses.count { it.status == HttpStatusCode.Created })
            assertEquals(9, responses.count { it.status == HttpStatusCode.Conflict })
        }

    @Test
    fun `invalid amounts currency idempotency and proof encodings fail without debit`() =
        testApplication {
            application { module(db, auth = AuthService(db, config)) }
            val token = client.login()
            for (body in
                listOf(
                    """{"terminalId":"$terminal","amountMinor":0,"currency":"AUD"}""",
                    """{"terminalId":"$terminal","amountMinor":1000001,"currency":"AUD"}""",
                    """{"terminalId":"$terminal","amountMinor":1.5,"currency":"AUD"}""",
                    """{"terminalId":"$terminal","amountMinor":1,"currency":"USD"}""",
                    """{"terminalId":"$terminal","amountMinor":1,"currency":"AUD","unexpected":true}""",
                )) assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .post("/api/v1/merchants/$merchant/payment-intents") {
                        bearerAuth(token)
                        header("Idempotency-Key", UUID.randomUUID())
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    .status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .post("/api/v1/merchants/$merchant/payment-intents") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"terminalId":"$terminal","amountMinor":1,"currency":"AUD"}""")
                    }
                    .status,
            )
            val intent = json(client.create(token))
            val id = intent.getValue("id").jsonPrimitive.content
            for (signature in listOf("not base64!", "AAAA", "A".repeat(137))) assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .authorise(
                        token,
                        id,
                        """{"terminalId":"$terminal","cardId":"$card","signatureDerBase64":"$signature"}""",
                    )
                    .status,
            )
            assertEquals(10000, balance())
            assertEquals(0, debits())
        }
}
