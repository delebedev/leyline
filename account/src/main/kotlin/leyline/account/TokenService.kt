package leyline.account

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Builds Arena-shaped JWTs for account authentication.
 *
 * Uses `alg:none` (unsigned) — the client accepts these because cert validation is off.
 * Claims match the real Wizards server shape (from proxy recordings).
 * Ready for RS256 signing via BouncyCastle when needed.
 */
class TokenService(
    private val clientId: String = DEFAULT_CLIENT_ID,
    private val roles: List<String> = DEFAULT_ROLES,
) {
    /** Token pair returned after login/register. */
    data class TokenPair(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long = ACCESS_EXPIRY_SECONDS,
    )

    /** Issue access + refresh tokens for an authenticated account. */
    fun issueTokens(account: Account): TokenPair = TokenPair(
        accessToken = buildAccessToken(account),
        refreshToken = buildRefreshToken(account),
    )

    /** Decode and validate a refresh token. Returns the persona ID or null if invalid/expired. */
    fun validateRefreshToken(token: String): String? {
        val payload = decodePayload(token) ?: return null
        val typ = CLAIM_TTYP.find(payload)?.groupValues?.get(1)
        if (typ != null && typ != "refresh") return null
        val exp = CLAIM_EXP.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (exp < nowSeconds()) return null
        return CLAIM_SUB.find(payload)?.groupValues?.get(1)
    }

    /** Decode and validate an access token. Returns the persona ID or null if invalid/expired. */
    fun validateAccessToken(token: String): String? {
        val payload = decodePayload(token) ?: return null
        val typ = CLAIM_TTYP.find(payload)?.groupValues?.get(1)
        if (typ != null && typ != "access") return null
        val exp = CLAIM_EXP.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (exp < nowSeconds()) return null
        return CLAIM_SUB.find(payload)?.groupValues?.get(1)
    }

    private fun buildAccessToken(account: Account): String {
        val now = nowSeconds()
        val payload = buildJsonObject {
            put("aud", JsonPrimitive(clientId))
            put("exp", JsonPrimitive(now + ACCESS_EXPIRY_SECONDS))
            put("iat", JsonPrimitive(now))
            put("iss", JsonPrimitive(account.accountId))
            put("sub", JsonPrimitive(account.personaId))
            put("wotc-ttyp", JsonPrimitive("access"))
            put("wotc-acct", JsonPrimitive(account.accountId))
            put("wotc-name", JsonPrimitive(account.displayName))
            put("wotc-domn", JsonPrimitive("wizards"))
            put("wotc-game", JsonPrimitive("arena"))
            put("wotc-flgs", JsonPrimitive(0))
            putJsonArray("wotc-rols") { roles.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("wotc-prms") {}
            putJsonArray("wotc-scps") { add(JsonPrimitive("first-party")) }
            put("wotc-pdgr", JsonPrimitive(account.personaId))
            putJsonArray("wotc-sgts") {}
            putJsonObject("wotc-socl") {}
            put("wotc-cnst", JsonPrimitive(0))
        }.toString()
        return encodeJwt(payload)
    }

    private fun buildRefreshToken(account: Account): String {
        val now = nowSeconds()
        val payload = buildJsonObject {
            put("aud", JsonPrimitive(clientId))
            put("exp", JsonPrimitive(now + REFRESH_EXPIRY_SECONDS))
            put("iat", JsonPrimitive(now))
            put("iss", JsonPrimitive(account.accountId))
            put("sub", JsonPrimitive(account.personaId))
            put("wotc-ttyp", JsonPrimitive("refresh"))
            put("wotc-domn", JsonPrimitive("wizards"))
            putJsonArray("wotc-scps") { add(JsonPrimitive("first-party")) }
            put("wotc-flgs", JsonPrimitive(0))
            put("wotc-acct", JsonPrimitive(account.accountId))
            put("wotc-pdgr", JsonPrimitive(account.personaId))
            putJsonObject("wotc-socl") {}
            put("wotc-cnst", JsonPrimitive(0))
        }.toString()
        return encodeJwt(payload)
    }

    private fun encodeJwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString(HEADER_NONE.toByteArray(Charsets.UTF_8)) +
            "." + enc.encodeToString(payload.toByteArray(Charsets.UTF_8)) + "."
    }

    private fun decodePayload(jwt: String): String? {
        val parts = jwt.split(".")
        if (parts.size < 2) return null
        return try {
            Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    companion object {
        const val DEFAULT_CLIENT_ID = "N8QFG8NEBJ5T35FB"
        const val ACCESS_EXPIRY_SECONDS = 960L // 16 minutes (matches real server)
        const val REFRESH_EXPIRY_SECONDS = 14 * 24 * 3600L // 14 days

        val DEFAULT_ROLES = listOf("MDNALPHA")
        val DEBUG_ROLES = DEFAULT_ROLES + "MTGA_DEBUG"

        private const val HEADER_NONE = """{"alg":"none","typ":"JWT"}"""

        private val CLAIM_EXP = """"exp"\s*:\s*(\d+)""".toRegex()
        private val CLAIM_SUB = """"sub"\s*:\s*"([^"]+)"""".toRegex()
        private val CLAIM_TTYP = """"wotc-ttyp"\s*:\s*"([^"]+)"""".toRegex()
    }
}
