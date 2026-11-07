package com.tapflow.api.auth

import com.tapflow.api.common.DomainError
import com.tapflow.api.merchants.MerchantAccess
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.URI
import java.util.UUID

fun Application.configureAuthentication(auth: AuthService) {
    install(Authentication) {
        jwt("access") {
            verifier(auth.tokens.verifier)
            validate { credential ->
                val p = credential.payload
                val user = p.subject?.canonicalUuid()
                val family = p.getClaim("sid").asString()?.canonicalUuid()
                val client = p.getClaim("client").asString()
                if (user != null && family != null && client in setOf("WEB", "MOBILE") && p.id?.canonicalUuid() != null)
                    auth.identity(user, family, client!!) else null
            }
            challenge { _, _ -> unauthorized() }
        }
    }
}

fun Application.configureRequestSecurity(limits: RateLimits) {
    fun ApplicationCall.isPairing() = request.httpMethod == HttpMethod.Post &&
        (request.path() == "/api/v1/terminals/pair" || Regex("/api/v1/merchants/[^/]+/terminals/pairing-codes").matches(request.path()))
    install(createApplicationPlugin("RequestSecurity") {
        onCall { call ->
            if (call.request.path().startsWith("/api/")) call.response.header(HttpHeaders.CacheControl, "no-store")
            if (call.isPairing()) limits.check("pairing:ip:${call.request.local.remoteHost}", 10, 60)
        }
        on(AuthenticationChecked) { call ->
            if (call.isPairing()) call.principal<Identity>()?.let {
                limits.check("pairing:user:${it.userId}", 10, 60)
            }
        }
    })
}

fun Route.authRoutes(auth: AuthService, merchants: MerchantAccess, limits: RateLimits) {
    route("/api/v1") {
        route("/auth") {
            for (clientType in listOf("WEB", "MOBILE")) {
                route("/${clientType.lowercase()}") {
                    post("/login") {
                        if (clientType == "WEB") call.checkBrowser(auth)
                        limits.check("login:ip:${call.request.local.remoteHost}", 20, 900)
                        val request = call.jsonBody<LoginRequest>()
                        val email = auth.credentials(request)
                        limits.check("login:account:$email", 5, 900)
                        val session = auth.login(request, clientType, call.requestId())
                        if (clientType == "WEB") { call.refreshCookie(session.refreshToken, auth); call.respond(session.web()) }
                        else call.respond(session)
                    }
                    post("/refresh") {
                        if (clientType == "WEB") call.checkBrowser(auth)
                        limits.check("refresh:ip:${call.request.local.remoteHost}", 60, 60)
                        val raw = if (clientType == "WEB") call.request.cookies["tapflow_refresh"] ?: unauthorized()
                            else call.jsonBody<RefreshRequest>().refreshToken
                        val session = auth.refresh(raw, clientType, call.requestId())
                        if (clientType == "WEB") { call.refreshCookie(session.refreshToken, auth); call.respond(session.web()) }
                        else call.respond(session)
                    }
                }
            }
            post("/web/logout") {
                call.checkBrowser(auth)
                limits.check("logout:ip:${call.request.local.remoteHost}", 60, 60)
                auth.logout(call.request.cookies["tapflow_refresh"], "WEB", call.requestId())
                call.refreshCookie("", auth, clear = true); call.respond(HttpStatusCode.NoContent)
            }
            post("/demo/customer-register") {
                if (!auth.config.registrationEnabled) throw DomainError(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "The resource was not found.")
                limits.check("registration:ip:${call.request.local.remoteHost}", 3, 3600)
                call.respond(HttpStatusCode.Created, auth.register(call.jsonBody<LoginRequest>(), call.requestId()))
            }
        }
        authenticate("access") {
            get("/me") { call.respond(auth.me(call.identity())) }
            post("/auth/mobile/logout") {
                limits.check("logout:ip:${call.request.local.remoteHost}", 60, 60)
                auth.logout(call.jsonBody<RefreshRequest>().refreshToken, "MOBILE", call.requestId(), call.identity())
                call.respond(HttpStatusCode.NoContent)
            }
            get("/merchants/{merchantId}") {
                val merchant = call.parameters["merchantId"]?.canonicalUuid() ?: invalid()
                call.respond(merchants.get(call.identity().userId, merchant))
            }
        }
    }
}

fun ApplicationCall.identity(): Identity = principal<Identity>() ?: unauthorized()
fun ApplicationCall.requestId(): UUID = UUID.fromString(checkNotNull(callId))
private suspend inline fun <reified T : Any> ApplicationCall.jsonBody(): T {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) invalid()
    return receive<T>()
}
fun String.canonicalUuid(): UUID? = takeIf { length == 36 }?.let { raw ->
    runCatching { UUID.fromString(raw) }.getOrNull()?.takeIf { it.toString().equals(raw, true) }
}
private fun ApplicationCall.checkBrowser(auth: AuthService) {
    val origin = request.header(HttpHeaders.Origin) ?: request.header(HttpHeaders.Referrer)?.let { raw ->
        runCatching { URI(raw).let { "${it.scheme}://${it.rawAuthority}" } }.getOrNull()
    }
    if (origin !in auth.config.allowedOrigins || request.header("X-TapFlow-CSRF") != "1")
        throw DomainError(HttpStatusCode.Forbidden, "CSRF_REJECTED", "This browser request is not allowed.")
}
private fun ApplicationCall.refreshCookie(value: String, auth: AuthService, clear: Boolean = false) {
    response.cookies.append(Cookie("tapflow_refresh", value, CookieEncoding.RAW,
        maxAge = if (clear) 0 else auth.config.refreshDays * 86_400,
        path = "/api/v1/auth/web", secure = auth.config.secureCookie, httpOnly = true, extensions = mapOf("SameSite" to "Lax")))
}
