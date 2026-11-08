package com.tapflow.protocol

class ReaderSession {
    enum class Phase {
        IDLE,
        WAITING,
        READING,
        RECEIVED,
        EXPIRED,
    }

    class Ticket(val generation: Long, val challenge: Challenge)

    private var generation = 0L
    private var challenge: Challenge? = null
    @Volatile
    var phase = Phase.IDLE
        private set

    @Synchronized
    fun start(value: Challenge) {
        generation++
        challenge = value
        phase = Phase.WAITING
    }

    @Synchronized
    fun tick(now: Long): Phase {
        if (
            phase in setOf(Phase.WAITING, Phase.READING) &&
                challenge?.expiresAtEpochMillis?.let { it <= now } == true
        ) {
            generation++
            challenge = null
            phase = Phase.EXPIRED
        }
        return phase
    }

    @Synchronized
    fun begin(now: Long): Ticket? {
        if (tick(now) != Phase.WAITING) return null
        phase = Phase.READING
        return Ticket(generation, checkNotNull(challenge))
    }

    @Synchronized
    fun complete(ticket: Ticket, now: Long): Boolean {
        if (tick(now) != Phase.READING || ticket.generation != generation) return false
        phase = Phase.RECEIVED
        challenge = null
        return true
    }

    @Synchronized
    fun retry(ticket: Ticket, now: Long): Boolean {
        if (tick(now) != Phase.READING || ticket.generation != generation) return false
        phase = Phase.WAITING
        return true
    }

    @Synchronized
    fun cancel() {
        generation++
        challenge = null
        phase = Phase.IDLE
    }
}
