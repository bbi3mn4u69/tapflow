package com.tapflow.api.auth

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class AuthRepository(private val nextId: () -> UUID = UUID::randomUUID) {
    class User(val id: UUID, val email: String, val hash: String, val type: String, val active: Boolean)
    class Session(val id: UUID, val userId: UUID, val familyId: UUID, val expiresAt: Instant, val revoked: Boolean, val rotated: Boolean)

    fun user(c: Connection, email: String): User? = c.prepareStatement("select * from users where email=?").use {
        it.setString(1, email); it.executeQuery().use { rows -> if (rows.next()) rows.user() else null }
    }
    fun lockUser(c: Connection, id: UUID): User? = c.prepareStatement("select * from users where id=? for update").use {
        it.setObject(1, id); it.executeQuery().use { rows -> if (rows.next()) rows.user() else null }
    }
    private fun ResultSet.user() = User(getObject("id", UUID::class.java), getString("email"), getString("password_hash"), getString("account_type"), getBoolean("active"))

    fun profile(c: Connection, user: User): SessionUser {
        val memberships = c.prepareStatement("""
            select m.id,m.name,mm.role from merchant_memberships mm
            join merchants m on m.id=mm.merchant_id where mm.user_id=? and m.status='ACTIVE' order by m.name,m.id
        """.trimIndent()).use {
            it.setObject(1, user.id); it.executeQuery().use { r -> buildList {
                while (r.next()) add(Membership(r.getString(1), r.getString(2), r.getString(3)))
            } }
        }
        return SessionUser(user.id.toString(), user.email, user.type, memberships)
    }

    fun insertSession(c: Connection, id: UUID, userId: UUID, family: UUID, client: String, hash: ByteArray, now: Instant, expiry: Instant, previous: UUID? = null) {
        c.prepareStatement("insert into refresh_sessions(id,user_id,family_id,client_type,token_hash,created_at,expires_at,rotated_from_id) values (?,?,?,?,?,?,?,?)").use {
            it.setObject(1, id); it.setObject(2, userId); it.setObject(3, family); it.setString(4, client)
            it.setBytes(5, hash); it.setTimestamp(6, Timestamp.from(now)); it.setTimestamp(7, Timestamp.from(expiry)); it.setObject(8, previous)
            it.executeUpdate()
        }
    }
    fun session(c: Connection, hash: ByteArray, client: String): Session? = c.prepareStatement("""
        select r.*,exists(select 1 from refresh_sessions child where child.rotated_from_id=r.id) as rotated
        from refresh_sessions r where token_hash=? and client_type=?
    """.trimIndent()).use {
        it.setBytes(1, hash); it.setString(2, client); it.executeQuery().use { r ->
            if (r.next()) Session(r.getObject("id", UUID::class.java), r.getObject("user_id", UUID::class.java),
                r.getObject("family_id", UUID::class.java), r.getTimestamp("expires_at").toInstant(), r.getTimestamp("revoked_at") != null, r.getBoolean("rotated")) else null
        }
    }
    fun revoke(c: Connection, family: UUID, now: Instant) {
        c.prepareStatement("update refresh_sessions set revoked_at=? where family_id=? and revoked_at is null").use {
            it.setTimestamp(1, Timestamp.from(now)); it.setObject(2, family); it.executeUpdate()
        }
    }
    fun revokeOne(c: Connection, id: UUID, now: Instant) {
        c.prepareStatement("update refresh_sessions set revoked_at=? where id=?").use {
            it.setTimestamp(1, Timestamp.from(now)); it.setObject(2, id); it.executeUpdate()
        }
    }
    fun activeIdentity(c: Connection, id: UUID, family: UUID, client: String, now: Instant): User? = c.prepareStatement("""
        select u.* from users u where u.id=? and u.active and exists (
            select 1 from refresh_sessions r where r.user_id=u.id and r.family_id=? and r.client_type=?
            and r.revoked_at is null and r.expires_at>?)
    """.trimIndent()).use {
        it.setObject(1, id); it.setObject(2, family); it.setString(3, client); it.setTimestamp(4, Timestamp.from(now))
        it.executeQuery().use { rows -> if (rows.next()) rows.user() else null }
    }
    fun register(c: Connection, id: UUID, email: String, hash: String): User? = c.prepareStatement("""
        insert into users(id,email,password_hash,account_type) values (?,?,?,'CUSTOMER') on conflict(email) do nothing returning *
    """.trimIndent()).use {
        it.setObject(1, id); it.setString(2, email); it.setString(3, hash)
        it.executeQuery().use { rows -> if (rows.next()) rows.user() else null }
    }
    fun audit(c: Connection, userId: UUID, event: String, requestId: UUID, now: Instant) {
        c.prepareStatement("insert into audit_events(id,actor_user_id,event_type,resource_type,resource_id,request_id,created_at) values (?, ?, ?, 'user', ?, ?, ?)").use {
            it.setObject(1, nextId()); it.setObject(2, userId); it.setString(3, event)
            it.setObject(4, userId); it.setObject(5, requestId); it.setTimestamp(6, Timestamp.from(now)); it.executeUpdate()
        }
    }
}
