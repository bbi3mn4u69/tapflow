package com.tapflow.protocol

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReaderSessionTest {
    private fun challenge() =
        Challenge(UUID.randomUUID(), UUID.randomUUID(), 2500, ByteArray(16), 2000)

    @Test
    fun `only one tag can claim a pending challenge and cancellation drops late results`() {
        val reader = ReaderSession()
        reader.start(challenge())
        val ticket = checkNotNull(reader.begin(1000))
        assertNull(reader.begin(1000))
        reader.cancel()
        assertFalse(reader.complete(ticket, 1000))
        reader.start(challenge())
        assertFalse(reader.retry(ticket, 1000))
        assertNotNull(reader.begin(1000))
    }

    @Test
    fun `tag loss retries the same challenge but expiry prevents reading or completion`() {
        val reader = ReaderSession()
        val challenge = challenge()
        reader.start(challenge)
        val first = checkNotNull(reader.begin(1000))
        assertTrue(reader.retry(first, 1500))
        val second = checkNotNull(reader.begin(1500))
        assertSame(challenge, second.challenge)
        assertFalse(reader.complete(second, 2000))
        assertEquals(ReaderSession.Phase.EXPIRED, reader.phase)
        reader.start(challenge)
        assertNull(reader.begin(2000))
        reader.start(challenge)
        assertEquals(ReaderSession.Phase.EXPIRED, reader.tick(2000))
    }
}
