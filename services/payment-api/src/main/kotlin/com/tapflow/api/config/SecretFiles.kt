package com.tapflow.api.config

import java.nio.file.Files
import java.nio.file.Path

object SecretFiles {
    fun load(env: Map<String, String>): Map<String, String> =
        env.toMutableMap().apply {
            for (key in
                listOf(
                    "DATABASE_PASSWORD",
                    "JWT_SIGNING_SECRET",
                    "SEED_OWNER_PASSWORD",
                    "SEED_OPERATOR_PASSWORD",
                    "SEED_CUSTOMER_PASSWORD",
                )) {
                val file = env["${key}_FILE"] ?: continue
                require(key !in env) { "Configure $key or ${key}_FILE, not both" }
                val value =
                    try {
                        val path = Path.of(file)
                        require(Files.isRegularFile(path) && Files.size(path) in 1..4096)
                        Files.readString(path).removeSuffix("\n").removeSuffix("\r")
                    } catch (_: Exception) {
                        throw IllegalArgumentException("Could not read secret file: $key")
                    }
                require(value.isNotEmpty() && '\n' !in value && '\r' !in value) {
                    "Invalid secret file: $key"
                }
                put(key, value)
            }
        }
}
