package com.tapflow.api.merchants

import com.tapflow.api.common.DomainError
import com.tapflow.api.db.Database
import io.ktor.http.HttpStatusCode
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class Merchant(
    val merchantId: String,
    val name: String,
    val slug: String,
    val status: String,
    val demo: Boolean = true,
)

class MerchantAccess(private val db: Database) {
    fun require(
        c: Connection,
        userId: UUID,
        merchantId: UUID,
        owner: Boolean = false,
        lock: Boolean = false,
    ): Merchant {
        return c.prepareStatement(
                """
                select m.id,m.name,m.slug,m.status,mm.role from merchants m
                join merchant_memberships mm on mm.merchant_id=m.id
                join users u on u.id=mm.user_id
                where m.id=? and mm.user_id=? and m.status='ACTIVE' and u.active and u.account_type='MERCHANT'
                """
                    .trimIndent() + if (lock) " for share of m,mm,u" else ""
            )
            .use {
                it.setObject(1, merchantId)
                it.setObject(2, userId)
                it.executeQuery().use { r ->
                    if (!r.next())
                        throw DomainError(
                            HttpStatusCode.NotFound,
                            "RESOURCE_NOT_FOUND",
                            "The resource was not found.",
                        )
                    if (owner && r.getString("role") != "OWNER")
                        throw DomainError(
                            HttpStatusCode.Forbidden,
                            "INSUFFICIENT_ROLE",
                            "An owner role is required.",
                        )
                    Merchant(
                        r.getString("id"),
                        r.getString("name"),
                        r.getString("slug"),
                        r.getString("status"),
                    )
                }
            }
    }

    suspend fun get(userId: UUID, merchantId: UUID): Merchant = db.query {
        require(it, userId, merchantId)
    }
}
