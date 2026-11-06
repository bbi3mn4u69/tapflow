package com.tapflow.api.db

import com.tapflow.api.config.DatabaseConfig
import com.tapflow.api.module
import de.mkammerer.argon2.Argon2Factory
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseTest {
    private val postgres = PostgreSQLContainer("postgres@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3")
    private val config get() = DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password, 2)

    @BeforeAll fun start() = postgres.start()
    @AfterAll fun stop() = postgres.stop()

    @Test
    fun `startup migrates a fresh database before reporting readiness`() {
        val db = assertDoesNotThrow<Database> { Database.open(config) }
        db.use { runBlocking { assertTrue(it.ready()) } }
        runBlocking { assertFalse(db.ready()) }
    }

    @Test
    fun `HTTP readiness follows the database while liveness remains independent`() {
        Database.open(config).use { db ->
            testApplication {
                application { module(db) }
                assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
                db.close()
                assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health/ready").status)
                assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
            }
        }
    }

    @Test
    fun `readiness rejects migration history drift`() {
        Database.open(config).use { db ->
            db.transaction { c -> c.createStatement().use { it.execute("update flyway_schema_history set checksum=checksum+1 where version='002'") } }
            try {
                runBlocking { assertFalse(db.ready()) }
            } finally {
                db.transaction { c -> c.createStatement().use { it.execute("update flyway_schema_history set checksum=checksum-1 where version='002'") } }
            }
        }
    }

    @Test
    fun `transaction failure rolls back its writes`() {
        Database.open(config).use { db ->
            val id = UUID.randomUUID()
            assertThrows(IllegalStateException::class.java) {
                db.transaction { c ->
                    c.prepareStatement("insert into merchants(id,name,slug) values (?, 'Rollback test', ?)").use {
                        it.setObject(1, id); it.setString(2, id.toString()); it.executeUpdate()
                    }
                    error("force rollback")
                }
            }
            db.transaction { c ->
                c.prepareStatement("select count(*) from merchants where id=?").use {
                    it.setObject(1, id)
                    it.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
                }
            }
        }
    }

    @Test
    fun `demo seeding is idempotent stores Argon2id hashes and creates no card keys`() {
        Database.open(config).use { db ->
            val owner = "only-test-owner-passphrase".toCharArray()
            val operator = "only-test-operator-passphrase".toCharArray()
            val customer = "only-test-customer-passphrase".toCharArray()
            repeat(2) { seedDemo(db, owner, operator, customer) }
            db.transaction { c ->
                c.createStatement().use { s ->
                    s.executeQuery("select count(*) from users").use { it.next(); assertEquals(3, it.getInt(1)) }
                    s.executeQuery("select count(*) from merchant_memberships").use { it.next(); assertEquals(2, it.getInt(1)) }
                    s.executeQuery("select count(*) from virtual_cards").use { it.next(); assertEquals(0, it.getInt(1)) }
                    s.executeQuery("select count(*) from audit_events where event_type='demo.seeded'").use { it.next(); assertEquals(1, it.getInt(1)) }
                    s.executeQuery("select password_hash from users where email='owner@example.test'").use {
                        assertTrue(it.next())
                        assertTrue(it.getString(1).startsWith("\$argon2id\$"))
                        assertTrue(Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).verify(it.getString(1), owner))
                    }
                }
            }
        }
    }
}
