package com.tapflow.api.auth

import java.security.MessageDigest
import java.time.Clock

class RateLimits(private val clock: Clock = Clock.systemUTC()) {
    private data class Bucket(var count: Int, val expires: Long)
    private val buckets = mutableMapOf<String, Bucket>()
    private var cleanupAt = 0L

    // shortcut: process-local limits suit one API replica; use shared limits before scaling replicas.
    @Synchronized
    fun check(key: String, limit: Int, seconds: Long) {
        val now = clock.instant().epochSecond
        if (now >= cleanupAt) {
            buckets.entries.removeIf { it.value.expires <= now }
            cleanupAt = now + 60
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val existing = buckets[hash]?.takeIf { it.expires > now }
        if (existing == null && buckets.size >= 10_000) throw Limited(60)
        val bucket = existing ?: Bucket(0, now + seconds).also { buckets[hash] = it }
        if (bucket.count >= limit) throw Limited((bucket.expires - now).coerceAtLeast(1))
        bucket.count++
    }
}

class Limited(val retryAfter: Long) : RuntimeException("Too many requests")
