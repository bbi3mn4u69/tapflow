package com.tapflow.api.config

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SecretFilesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `reads one line secrets and preserves other configuration`() {
        val secret = directory.resolve("db")
        Files.writeString(secret, "fixture-secret-value\n")
        val env =
            SecretFiles.load(
                mapOf("DATABASE_PASSWORD_FILE" to secret.toString(), "APP_ENV" to "production")
            )
        assertEquals("fixture-secret-value", env["DATABASE_PASSWORD"])
        assertEquals("production", env["APP_ENV"])
    }

    @Test
    fun `rejects ambiguous missing multiline and oversized files without leaking contents`() {
        val path = directory.resolve("secret")
        Files.writeString(path, "private-fixture-only\nsecond-line")
        for (env in
            listOf(
                mapOf("JWT_SIGNING_SECRET_FILE" to path.toString()),
                mapOf(
                    "JWT_SIGNING_SECRET" to "private-fixture-only",
                    "JWT_SIGNING_SECRET_FILE" to path.toString(),
                ),
                mapOf("JWT_SIGNING_SECRET_FILE" to directory.resolve("missing").toString()),
            )) {
            val error = assertThrows(IllegalArgumentException::class.java) { SecretFiles.load(env) }
            assertFalse(error.message.orEmpty().contains("private-fixture-only"))
        }
        Files.writeString(path, "x".repeat(4097))
        assertThrows(IllegalArgumentException::class.java) {
            SecretFiles.load(mapOf("JWT_SIGNING_SECRET_FILE" to path.toString()))
        }
    }
}
