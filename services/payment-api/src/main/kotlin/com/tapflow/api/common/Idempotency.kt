package com.tapflow.api.common

import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object Idempotency {
    class Response(val status: Int, val body: JsonObject)

    fun execute(
        c: Connection,
        scope: String,
        key: UUID,
        hash: ByteArray,
        now: Instant,
        nextId: () -> UUID = UUID::randomUUID,
        produce: () -> Response,
    ): Response {
        c.prepareStatement(
                "insert into idempotency_records(id,scope,idempotency_key,request_sha256,created_at,expires_at) values (?,?,?,?,?,?) on conflict(scope,idempotency_key) do nothing"
            )
            .use {
                it.setObject(1, nextId())
                it.setString(2, scope)
                it.setObject(3, key)
                it.setBytes(4, hash)
                it.setTimestamp(5, Timestamp.from(now))
                it.setTimestamp(6, Timestamp.from(now.plusSeconds(86400)))
                it.executeUpdate()
            }
        val replay =
            c.prepareStatement(
                    "select request_sha256,response_status,response_json from idempotency_records where scope=? and idempotency_key=? for update"
                )
                .use {
                    it.setString(1, scope)
                    it.setObject(2, key)
                    it.executeQuery().use { rows ->
                        check(rows.next())
                        if (!MessageDigest.isEqual(hash, rows.getBytes(1)))
                            throw DomainError(
                                HttpStatusCode.Conflict,
                                "IDEMPOTENCY_KEY_REUSED",
                                "This key was used for a different request.",
                            )
                        rows.getString(3)?.let { body ->
                            Response(rows.getInt(2), Json.parseToJsonElement(body).jsonObject)
                        }
                    }
                }
        if (replay != null) return replay
        val response = produce()
        return c.prepareStatement(
                "update idempotency_records set response_status=?,response_json=?::jsonb where scope=? and idempotency_key=? returning response_status,response_json"
            )
            .use {
                it.setInt(1, response.status)
                it.setString(2, response.body.toString())
                it.setString(3, scope)
                it.setObject(4, key)
                it.executeQuery().use { rows ->
                    check(rows.next())
                    Response(rows.getInt(1), Json.parseToJsonElement(rows.getString(2)).jsonObject)
                }
            }
    }
}
