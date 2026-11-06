package com.tapflow.api.db

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaTest {
    private val postgres = PostgreSQLContainer("postgres@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3")
    private lateinit var flyway: Flyway

    @BeforeAll
    fun startDatabase() {
        postgres.start()
        flyway = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .defaultSchema("tapflow").schemas("tapflow").load()
        flyway.migrate()
    }

    @AfterAll
    fun stopDatabase() = postgres.stop()

    private fun connect(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).apply {
        schema = "tapflow"
    }

    private fun rejects(c: Connection, sql: String, state: String) {
        val error = assertThrows(SQLException::class.java) { c.createStatement().use { it.execute(sql) } }
        assertEquals(state, error.sqlState)
    }

    @Test
    fun `fresh migrations create the complete domain schema and reapply cleanly`() = connect().use { connection ->
        val names = connection.createStatement().use { statement ->
            statement.executeQuery("select tablename from pg_tables where schemaname='tapflow'").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }
        assertTrue(names.containsAll(setOf("users", "merchants", "merchant_memberships", "terminals",
            "terminal_pairing_codes", "virtual_cards", "wallets", "payment_intents", "refunds", "wallet_ledger",
            "idempotency_records", "refresh_sessions", "audit_events")), names.toString())
        assertTrue(flyway.validateWithResult().validationSuccessful)
        assertEquals(0, flyway.migrate().migrationsExecuted)
    }

    @Test
    fun `amount bounds and tenant foreign keys reject invalid payment rows`() = connect().use { c ->
        val customer = UUID.randomUUID(); val merchant = UUID.randomUUID(); val otherMerchant = UUID.randomUUID()
        val terminal = UUID.randomUUID()
        c.createStatement().use { s ->
            s.execute("insert into users(id,email,password_hash,account_type) values ('$customer','${customer}@example.test','\$argon2id\$test','CUSTOMER')")
            s.execute("insert into merchants(id,name,slug) values ('$merchant','Test','$merchant'),('$otherMerchant','Other','$otherMerchant')")
            s.execute("insert into terminals(id,merchant_id,label,installation_id,paired_by_user_id) values ('$terminal','$merchant','Test','${UUID.randomUUID()}','$customer')")
        }
        for ((amount, tenant) in listOf(0L to merchant, -1L to merchant, 1_000_001L to merchant, 25L to otherMerchant)) {
            rejects(c, "insert into payment_intents(id,merchant_id,terminal_id,amount_minor,challenge_nonce,expires_at) values ('${UUID.randomUUID()}','$tenant','$terminal',$amount,decode(repeat('00',16),'hex'),now()+interval '90 seconds')",
                if (tenant == otherMerchant) "23503" else "23514")
        }
    }

    @Test
    fun `initial funding is unique and ledger records are append only`() = connect().use { c ->
        val user = UUID.randomUUID(); val card = UUID.randomUUID(); val wallet = UUID.randomUUID()
        c.createStatement().use { s ->
            s.execute("insert into users(id,email,password_hash,account_type) values ('$user','${user}@example.test','\$argon2id\$test','CUSTOMER')")
            s.execute("insert into virtual_cards(id,owner_user_id,label,public_key_spki) values ('$card','$user','Test',decode(replace('$card','-',''),'hex'))")
            s.execute("insert into wallets(id,card_id,balance_minor) values ('$wallet','$card',10000)")
            s.execute("insert into wallet_ledger(id,wallet_id,entry_type,amount_minor,balance_after_minor) values ('${UUID.randomUUID()}','$wallet','DEMO_FUNDING',10000,10000)")
        }
        for ((sql, state) in listOf(
            "insert into wallet_ledger(id,wallet_id,entry_type,amount_minor,balance_after_minor) values ('${UUID.randomUUID()}','$wallet','DEMO_FUNDING',10000,10000)" to "23505",
            "update wallet_ledger set amount_minor=1 where wallet_id='$wallet'" to "23514",
            "delete from wallet_ledger where wallet_id='$wallet'" to "23514",
            "update wallets set balance_minor=-1 where id='$wallet'" to "23514",
        )) rejects(c, sql, state)
    }

    @Test
    fun `wallet currency and card key size are constrained`(): Unit = connect().use { c ->
        val user = UUID.randomUUID(); val card = UUID.randomUUID()
        c.createStatement().use { s ->
            s.execute("insert into users(id,email,password_hash,account_type) values ('$user','${user}@example.test','\$argon2id\$test','CUSTOMER')")
            s.execute("insert into virtual_cards(id,owner_user_id,label,public_key_spki) values ('$card','$user','Test',decode(replace('$card','-',''),'hex'))")
        }
        rejects(c, "insert into wallets(id,card_id,currency) values ('${UUID.randomUUID()}','$card','USD')", "23514")
        rejects(c, "update virtual_cards set public_key_spki=decode(repeat('00',1025),'hex') where id='$card'", "23514")
    }
}
