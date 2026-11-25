package com.tapflow.api.db

import com.tapflow.api.config.SecretFiles
import com.tapflow.api.config.AppConfig
import de.mkammerer.argon2.Argon2Factory
import java.util.UUID

fun main() {
    val env = SecretFiles.load(System.getenv())
    val config = AppConfig.fromEnvironment(env)
    require(config.environment in setOf("development", "test")) { "Demo seeding is restricted to development and test" }
    val passwords = listOf("SEED_OWNER_PASSWORD", "SEED_OPERATOR_PASSWORD", "SEED_CUSTOMER_PASSWORD").map { name ->
        val password = env[name]
        require(password != null && !password.startsWith("REPLACE_") && password.length in 12..128) {
            "Supply $name locally (12–128 characters)"
        }
        password.toCharArray()
    }
    try {
        Database.open(config.database).use { seedDemo(it, passwords[0], passwords[1], passwords[2]) }
        println("Demo merchant, owner, operator and customer are ready. Passwords were not printed. DEMO / NO REAL MONEY.")
    } finally {
        passwords.forEach { it.fill('\u0000') }
    }
}

fun seedDemo(database: Database, ownerPassword: CharArray, operatorPassword: CharArray, customerPassword: CharArray) {
    require(listOf(ownerPassword, operatorPassword, customerPassword).all { it.size in 12..128 }) {
        "Demo passwords must have 12–128 characters"
    }
    val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
    database.transaction { connection ->
        fun account(email: String, type: String, password: CharArray): UUID {
            connection.prepareStatement("select id,account_type,active from users where email=?").use {
                it.setString(1, email)
                it.executeQuery().use { rows ->
                    if (rows.next()) {
                        check(rows.getString("account_type") == type && rows.getBoolean("active")) { "Existing demo account conflicts with seed configuration" }
                        return rows.getObject("id", UUID::class.java)
                    }
                }
            }
            val id = UUID.randomUUID()
            connection.prepareStatement("insert into users(id,email,password_hash,account_type) values (?,?,?,?)").use {
                it.setObject(1, id); it.setString(2, email); it.setString(3, argon2.hash(3, 65_536, 1, password)); it.setString(4, type)
                it.executeUpdate()
            }
            return id
        }
        // A seed run is an operator action; serialize reruns without changing existing passwords.
        connection.createStatement().use { it.execute("select pg_advisory_xact_lock(1413894220)") }
        val owner = account("owner@example.test", "MERCHANT", ownerPassword)
        val operator = account("operator@example.test", "MERCHANT", operatorPassword)
        account("customer@example.test", "CUSTOMER", customerPassword)
        val merchantId = connection.prepareStatement("select id,status from merchants where slug='tapflow-demo-store'").use {
            it.executeQuery().use { rows ->
                if (rows.next()) {
                    check(rows.getString("status") == "ACTIVE") { "Existing demo merchant is not active" }
                    rows.getObject("id", UUID::class.java)
                } else null
            }
        } ?: UUID.randomUUID().also { id ->
            connection.prepareStatement("insert into merchants(id,name,slug) values (?,'TapFlow Demo Store','tapflow-demo-store')").use {
                it.setObject(1, id); it.executeUpdate()
            }
        }
        for ((userId, role) in listOf(owner to "OWNER", operator to "OPERATOR")) {
            connection.prepareStatement("insert into merchant_memberships(user_id,merchant_id,role) values (?,?,?) on conflict do nothing").use {
                it.setObject(1, userId); it.setObject(2, merchantId); it.setString(3, role); it.executeUpdate()
            }
            connection.prepareStatement("select role from merchant_memberships where user_id=? and merchant_id=?").use {
                it.setObject(1, userId); it.setObject(2, merchantId)
                it.executeQuery().use { rows -> check(rows.next() && rows.getString(1) == role) { "Existing demo membership has a different role" } }
            }
        }
        connection.prepareStatement("""
            insert into audit_events(id,merchant_id,event_type,resource_type,resource_id,details_json,request_id)
            select ?,?,'demo.seeded','merchant',?,'{"source":"seed-cli"}'::jsonb,?
            where not exists (select 1 from audit_events where merchant_id=? and event_type='demo.seeded')
        """.trimIndent()).use {
            it.setObject(1, UUID.randomUUID()); it.setObject(2, merchantId); it.setObject(3, merchantId)
            it.setObject(4, UUID.randomUUID()); it.setObject(5, merchantId); it.executeUpdate()
        }
    }
}
