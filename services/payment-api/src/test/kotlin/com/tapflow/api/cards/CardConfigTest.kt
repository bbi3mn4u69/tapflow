package com.tapflow.api.cards

import com.tapflow.api.config.CardConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CardConfigTest {
    @Test
    fun `funding is bounded and enrollment requires an explicit production flag`() {
        assertTrue(CardConfig.fromEnvironment(emptyMap()).enrollmentEnabled)
        assertFalse(CardConfig.fromEnvironment(mapOf("APP_ENV" to "production")).enrollmentEnabled)
        assertTrue(
            CardConfig.fromEnvironment(
                    mapOf("APP_ENV" to "production", "DEMO_CARD_ENROLLMENT_ENABLED" to "true")
                )
                .enrollmentEnabled
        )
        for (value in listOf("0", "-1", "1000001", "not-an-amount")) assertThrows<
            IllegalArgumentException
        > {
            CardConfig.fromEnvironment(mapOf("DEMO_INITIAL_BALANCE_MINOR" to value))
        }
        assertThrows<IllegalArgumentException> {
            CardConfig.fromEnvironment(mapOf("DEMO_CARD_ENROLLMENT_ENABLED" to "yes"))
        }
    }
}
