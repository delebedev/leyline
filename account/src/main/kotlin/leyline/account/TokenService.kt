package leyline.account

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.Base64

/**
 * Builds minimal local JWTs for account bootstrap.
 *
 * Tokens are unsigned (`alg:none`) because the client accepts local bootstrap
 * tokens without signature verification on this path.
 */
class TokenService(
    private val clientId: String = DEFAULT_CLIENT_ID,
) {
    data class TokenPair(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long = ACCESS_EXPIRY_SECONDS,
    )

    fun issueTokens(account: Account): TokenPair = TokenPair(
        accessToken = buildToken(account, ACCESS_EXPIRY_SECONDS),
        refreshToken = buildToken(account, REFRESH_EXPIRY_SECONDS),
    )

    fun validateRefreshToken(token: String): String? = validateToken(token)

    fun validateAccessToken(token: String): String? = validateToken(token)

    private fun validateToken(token: String): String? {
        val payload = decodePayload(token) ?: return null
        val exp = CLAIM_EXP.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (exp < nowSeconds()) return null
        return CLAIM_SUB.find(payload)?.groupValues?.get(1)
    }

    private fun buildToken(account: Account, ttlSeconds: Long): String {
        val now = nowSeconds()
        val payload = buildJsonObject {
            put("aud", JsonPrimitive(clientId))
            put("exp", JsonPrimitive(now + ttlSeconds))
            put("iat", JsonPrimitive(now))
            put("iss", JsonPrimitive(account.accountId))
            put("sub", JsonPrimitive(account.personaId))
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
        const val DEFAULT_CLIENT_ID = "leyline"
        const val ACCESS_EXPIRY_SECONDS = 960L
        const val REFRESH_EXPIRY_SECONDS = 14 * 24 * 3600L

        private const val HEADER_NONE = """{"alg":"none","typ":"JWT"}"""

        private val CLAIM_EXP = """"exp"\s*:\s*(\d+)""".toRegex()
        private val CLAIM_SUB = """"sub"\s*:\s*"([^"]+)"""".toRegex()
    }
}
