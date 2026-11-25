package com.tapflow.api.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AppConfigTest {
    private val env = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://db.example.test:5432/tapflow?sslmode=require",
        "DATABASE_USER" to "tapflow",
        "DATABASE_PASSWORD" to "not-a-real-password",
        "APP_ENV" to "development",
        "DEMO_MODE" to "true",
    )

    @Test
    fun `loads explicit database credentials and bounded pool settings`() {
        val config = AppConfig.fromEnvironment(env + ("DATABASE_POOL_SIZE" to "3"))
        assertEquals(3, config.database.poolSize)
        assertEquals(8080, config.port)
        assertEquals("tapflow", config.database.user)
        assertFalse(config.toString().contains(env.getValue("DATABASE_PASSWORD")))
    }

    @Test
    fun `rejects absent secrets without including them in errors`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            AppConfig.fromEnvironment(env - "DATABASE_PASSWORD")
        }
        assertTrue(failure.message.orEmpty().contains("DATABASE_PASSWORD"))
    }

    @Test
    fun `rejects credentials embedded in URLs`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppConfig.fromEnvironment(env + ("DATABASE_URL" to "jdbc:postgresql://user:secret@db/tapflow"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppConfig.fromEnvironment(env + ("DATABASE_URL" to "jdbc:postgresql://db/tapflow?password=secret"))
        }
    }

    @Test
    fun `requires TLS for non-loopback database connections`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppConfig.fromEnvironment(env + ("DATABASE_URL" to "jdbc:postgresql://remote.example.test/tapflow"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppConfig.fromEnvironment(env + ("DATABASE_URL" to "jdbc:postgresql://remote.example.test/tapflow?sslmode=require&sslmode=disable"))
        }
    }

    @Test
    fun `validates pool and port bounds`() {
        for (value in listOf("0", "21", "bad")) {
            assertThrows(IllegalArgumentException::class.java) {
                AppConfig.fromEnvironment(env + ("DATABASE_POOL_SIZE" to value))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { AppConfig.fromEnvironment(env + ("API_PORT" to "0")) }
    }
    @Test fun `accepts Railway PORT while explicit API_PORT has precedence`() {
        assertEquals(4321,AppConfig.fromEnvironment(env+("PORT" to "4321")).port)
        assertEquals(8081,AppConfig.fromEnvironment(env+mapOf("PORT" to "4321","API_PORT" to "8081")).port)
        for(raw in listOf("0","65536","invalid"))assertThrows(IllegalArgumentException::class.java){AppConfig.fromEnvironment(env+("PORT" to raw))}
    }
    @Test fun `rejects enabling a public reset surface`() {
        assertThrows(IllegalArgumentException::class.java){AppConfig.fromEnvironment(env+("DEMO_RESET_ENABLED" to "true"))}
    }

}
