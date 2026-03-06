package leyline.account

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("leyline.account.routes")
private val json = Json { ignoreUnknownKeys = true }

/**
 * Install all account server routes into a Ktor [Route].
 * Real handlers for auth/register/profile, stubs for age-gate/moderate/skus.
 */
fun Route.accountRoutes(store: AccountStore, tokens: TokenService, fdHost: String) {
    loginRoute(store, tokens)
    registerRoute(store, tokens)
    profileRoute(store, tokens)
    doorbellRoute(fdHost)
    ageGateStub()
    moderateStub()
    skusStub()
    catchAll()
}

// -- Real handlers ------------------------------------------------------------

private fun Route.loginRoute(store: AccountStore, tokens: TokenService) {
    post("/auth/oauth/token") {
        val body = call.receiveText()
        val params = parseFormEncoded(body)
        val grantType = params["grant_type"]

        when (grantType) {
            "password" -> {
                val email = params["username"]
                    ?: return@post call.respondError(AccountError.MISSING_FIELD)
                val password = params["password"]
                    ?: return@post call.respondError(AccountError.MISSING_PASSWORD)
                val account = store.authenticate(email, password)
                    ?: return@post call.respondError(AccountError.INVALID_CREDENTIALS)
                val pair = tokens.issueTokens(account)
                log.info("Login: {} -> {}", email, account.accountId.take(8))
                call.respondText(
                    loginResponseJson(account, pair),
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }

            "refresh_token" -> {
                val refreshToken = params["refresh_token"]
                    ?: return@post call.respondError(AccountError.MISSING_REFRESH_TOKEN)
                val personaId = tokens.validateRefreshToken(refreshToken)
                    ?: return@post call.respondError(AccountError.INVALID_CLIENT)
                val account = store.findByPersonaId(personaId)
                    ?: return@post call.respondError(AccountError.INVALID_CLIENT)
                val pair = tokens.issueTokens(account)
                log.info("Token refresh: {}", account.accountId.take(8))
                call.respondText(
                    loginResponseJson(account, pair),
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }

            else -> call.respondError(AccountError.UNSUPPORTED_GRANT_TYPE)
        }
    }
}

private fun Route.registerRoute(store: AccountStore, tokens: TokenService) {
    post("/accounts/register") {
        val body = call.receiveText()
        val req = try {
            json.decodeFromString<RegisterRequest>(body)
        } catch (_: Exception) {
            return@post call.respondError(AccountError.INVALID_REQUEST)
        }

        // Check duplicate email
        if (store.findByEmail(req.email) != null) {
            return@post call.respondError(AccountError.INVALID_EMAIL)
        }

        val account = try {
            store.create(
                email = req.email,
                password = req.password,
                displayName = req.displayName,
                country = req.country,
                dob = req.dateOfBirth,
            )
        } catch (e: Exception) {
            log.error("Registration failed: {}", e.message)
            return@post call.respondError(AccountError.INVALID_EMAIL)
        }

        val pair = tokens.issueTokens(account)
        log.info("Register: {} -> {} ({})", req.email, account.displayName, account.accountId.take(8))

        call.respondText(
            registerResponseJson(account, pair),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }
}

private fun Route.profileRoute(store: AccountStore, tokens: TokenService) {
    get("/profile") {
        val bearer = call.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
        if (bearer == null) {
            return@get call.respondError(AccountError.MISSING_AUTH)
        }
        val personaId = tokens.validateRefreshToken(bearer)
            ?: extractPersonaIdFromJwt(bearer)
        if (personaId == null) {
            return@get call.respondError(AccountError.INVALID_TOKEN)
        }
        val account = store.findByPersonaId(personaId)
            ?: return@get call.respondError(AccountError.NOT_FOUND)
        log.debug("Profile: {}", account.accountId.take(8))
        call.respondText(
            profileResponseJson(account),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }
}

// -- Doorbell -----------------------------------------------------------------

private fun Route.doorbellRoute(fdHost: String) {
    post("/api/doorbell/api/v2/ring") {
        call.receiveText() // drain body
        log.info("Doorbell: FdURI={}", fdHost)
        val response = buildJsonObject {
            put("FdURI", fdHost)
            putJsonArray("BundleManifests") {}
        }
        call.respondText(response.toString(), ContentType.Application.Json, HttpStatusCode.OK)
    }
}

// -- Stubs --------------------------------------------------------------------

private fun Route.ageGateStub() {
    post("/accounts/requires-age-gate") {
        call.receiveText()
        call.respondText(
            """{"requiresAgeGate":false}""",
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }
}

private fun Route.moderateStub() {
    post("/accounts/moderate") {
        call.receiveText()
        call.respondText("", ContentType.Application.Json, HttpStatusCode.OK)
    }
}

private fun Route.skusStub() {
    get("/xsollaconnector/client/skus") {
        call.respondText(
            """{"items":[]}""",
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }
}

private fun Route.catchAll() {
    route("{...}") {
        handle {
            log.warn("Unhandled: {} {}", call.request.httpMethod.value, call.request.path())
            if (call.request.httpMethod == HttpMethod.Post) call.receiveText()
            call.respondText("{}", ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}

// -- JSON response builders (injection-safe via kotlinx.serialization) --------

private fun loginResponseJson(account: Account, pair: TokenService.TokenPair): String =
    buildJsonObject {
        put("access_token", pair.accessToken)
        put("refresh_token", pair.refreshToken)
        put("expires_in", pair.expiresIn)
        put("token_type", "Bearer")
        put("persona_id", account.personaId)
        put("account_id", account.accountId)
        put("display_name", account.displayName)
    }.toString()

private fun registerResponseJson(account: Account, pair: TokenService.TokenPair): String =
    buildJsonObject {
        put("accountID", account.accountId)
        put("email", account.email)
        put("displayName", account.displayName)
        put("domainID", "wizards")
        put("externalID", "")
        putJsonObject("tokens") {
            put("access_token", pair.accessToken)
            put("refresh_token", pair.refreshToken)
            put("expires_in", pair.expiresIn)
            put("token_type", "Bearer")
        }
    }.toString()

private fun profileResponseJson(account: Account): String =
    buildJsonObject {
        put("accountID", account.accountId)
        put("ccpaProtectData", false)
        put("countryCode", account.country)
        put("createdAt", account.createdAt)
        put("dataOptIn", true)
        put("displayName", account.displayName)
        put("email", account.email)
        put("emailOptIn", false)
        put("emailVerified", false)
        put("externalID", "")
        put("gameID", "arena")
        put("languageCode", "en-US")
        put("parentalConsentState", 3)
        put("personaID", account.personaId)
        putJsonObject("presenceSettings") {
            put("socialMode", "PUBLIC")
        }
        put("targetedAnalyticsOptOut", false)
    }.toString()

// -- Utilities ----------------------------------------------------------------

private fun parseFormEncoded(body: String): Map<String, String> {
    if (body.isBlank()) return emptyMap()
    return body.split("&").associate { part ->
        val (k, v) = part.split("=", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else it[0] to ""
        }
        java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
    }
}

/**
 * Extract persona ID (sub claim) from a JWT without full validation.
 * Used for profile lookups where the access token is passed as Bearer.
 */
private fun extractPersonaIdFromJwt(jwt: String): String? {
    val parts = jwt.split(".")
    if (parts.size < 2) return null
    return try {
        val payload = java.util.Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8)
        """"sub"\s*:\s*"([^"]+)"""".toRegex().find(payload)?.groupValues?.get(1)
    } catch (_: Exception) {
        null
    }
}

// -- Request DTOs -------------------------------------------------------------

@Serializable
private data class RegisterRequest(
    val displayName: String,
    val email: String,
    val password: String,
    val country: String = "US",
    val dateOfBirth: String = "1990-01-01",
    val acceptedTC: Boolean = true,
    val emailOptIn: Boolean = false,
    val dataShareOptIn: Boolean = false,
    val socialToken: String = "",
    val socialType: String = "credential",
    val dryRun: Boolean = false,
)
