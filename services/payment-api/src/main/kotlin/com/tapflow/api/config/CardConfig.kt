package com.tapflow.api.config

class CardConfig(val initialBalanceMinor: Long = 10_000, val enrollmentEnabled: Boolean = true) {
    init {
        require(initialBalanceMinor in 1..1_000_000) { "Invalid demo initial balance" }
    }

    companion object {
        fun fromEnvironment(env: Map<String, String>): CardConfig {
            val enabled =
                env["DEMO_CARD_ENROLLMENT_ENABLED"]
                    ?: if ((env["APP_ENV"] ?: "development") in setOf("development", "test")) "true"
                    else "false"
            require(enabled in setOf("true", "false")) { "Invalid enrollment flag" }
            return CardConfig(
                requireNotNull((env["DEMO_INITIAL_BALANCE_MINOR"] ?: "10000").toLongOrNull()) {
                    "Invalid demo initial balance"
                },
                enabled == "true",
            )
        }
    }
}
