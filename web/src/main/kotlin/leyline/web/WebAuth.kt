package leyline.web

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val WEB_SESSION_COOKIE = "web_session"
const val WEB_SESSION_MAX_AGE_SECONDS = 30 * 24 * 60 * 60
const val DEV_WEB_AUTH_SECRET = "dev-web-auth-secret"

private const val LOGIN_CODE_MINUTES = 10
const val MAX_AUTH_ATTEMPTS = 5
private const val RESEND_COOLDOWN_SECONDS = 30L
const val SESSION_IDLE_SECONDS = 30L * 24 * 60 * 60
private const val SESSION_ABSOLUTE_SECONDS = 90L * 24 * 60 * 60

data class LoginCodeEmail(
    val to: String,
    val code: String,
    val expiresInMinutes: Int,
    val idempotencyKey: String,
)

interface EmailSender {
    suspend fun sendLoginCode(input: LoginCodeEmail)
}

class DevEmailSender : EmailSender {
    private val sent = ConcurrentHashMap<String, LoginCodeEmail>()

    override suspend fun sendLoginCode(input: LoginCodeEmail) {
        sent[normalizeEmail(input.to)] = input
    }

    fun latestCode(email: String): String? = sent[normalizeEmail(email)]?.code
}

class ResendEmailSender(
    private val apiKey: String,
    private val from: String,
    private val http: HttpClient = HttpClient.newHttpClient(),
) : EmailSender {
    override suspend fun sendLoginCode(input: LoginCodeEmail) {
        val body =
            """
            {"from":"${from.jsonEscape()}","to":["${input.to.jsonEscape()}"],"subject":"Your Leyline login code","text":"Your login code is ${input.code}. It expires in ${input.expiresInMinutes} minutes."}
            """.trimIndent()
        val request =
            HttpRequest
                .newBuilder(URI.create("https://api.resend.com/emails"))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", input.idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) error("Resend returned HTTP ${response.statusCode()}")
    }
}

data class AuthRateLimitConfig(
    val enabled: Boolean = true,
    val loginLimit: Int = 10,
    val loginWindowMs: Long = 60_000,
) {
    companion object {
        fun fromEnv(): AuthRateLimitConfig =
            AuthRateLimitConfig(
                enabled = System.getenv("AUTH_RATE_LIMIT_ENABLED")?.toBooleanStrictOrNull() ?: true,
                loginLimit = System.getenv("AUTH_LOGIN_RATE_LIMIT")?.toIntOrNull() ?: 10,
                loginWindowMs = System.getenv("AUTH_LOGIN_RATE_WINDOW_MS")?.toLongOrNull() ?: 60_000,
            )

        fun disabled(): AuthRateLimitConfig = AuthRateLimitConfig(enabled = false)
    }
}

interface RequestRateLimiter {
    fun check(
        key: String,
        limit: Int,
        windowMs: Long,
    ): Boolean
}

object NoopRateLimiter : RequestRateLimiter {
    override fun check(
        key: String,
        limit: Int,
        windowMs: Long,
    ): Boolean = true
}

class InMemoryRateLimiter : RequestRateLimiter {
    private val windows = ConcurrentHashMap<String, MutableList<Long>>()

    override fun check(
        key: String,
        limit: Int,
        windowMs: Long,
    ): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = windows.computeIfAbsent(key) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.removeAll { it < now - windowMs }
            if (timestamps.size >= limit) return false
            timestamps.add(now)
        }
        return true
    }
}

data class WebPlayer(
    val playerId: String,
    val email: String,
)

interface WebAuthStore {
    fun startChallenge(
        challenge: WebLoginChallenge,
        resendCooldownSeconds: Long,
        now: Instant,
    ): ChallengeStartResult

    fun consumeChallenge(
        email: String,
        codeHash: String,
        now: Instant,
    ): ChallengeConsumeResult

    fun findOrCreatePlayer(email: String): WebPlayer

    fun saveSession(session: WebSession)

    fun findSession(
        tokenHash: String,
        now: Instant,
    ): WebPlayer?

    fun revokeSession(
        tokenHash: String,
        now: Instant,
    )
}

data class WebLoginChallenge(
    val id: String,
    val email: String,
    val codeHash: String,
    val expiresAt: Instant,
    val createdAt: Instant,
    val requestIpHash: String?,
    val userAgent: String?,
)

data class WebSession(
    val id: String,
    val playerId: String,
    val tokenHash: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val idleExpiresAt: Instant,
    val absoluteExpiresAt: Instant,
    val ipHash: String?,
    val userAgent: String?,
)

sealed class ChallengeStartResult {
    data object Sent : ChallengeStartResult()

    data object Cooldown : ChallengeStartResult()
}

sealed class ChallengeConsumeResult {
    data object Accepted : ChallengeConsumeResult()

    data object InvalidOrExpired : ChallengeConsumeResult()

    data object TooManyAttempts : ChallengeConsumeResult()
}

sealed class StartLoginResult {
    data object Sent : StartLoginResult()

    data object Cooldown : StartLoginResult()

    data object RateLimited : StartLoginResult()

    data object EmailSendFailed : StartLoginResult()
}

sealed class VerifyLoginResult {
    data class Success(
        val token: String,
        val player: WebPlayer,
    ) : VerifyLoginResult()

    data object InvalidOrExpired : VerifyLoginResult()

    data object TooManyAttempts : VerifyLoginResult()

    data object RateLimited : VerifyLoginResult()
}

class InMemoryWebAuthStore : WebAuthStore {
    private data class Challenge(
        val challenge: WebLoginChallenge,
        val usedAt: Instant? = null,
        val attemptCount: Int = 0,
    )

    private data class Session(
        val session: WebSession,
        val revokedAt: Instant? = null,
    )

    private val challenges = ConcurrentHashMap<String, Challenge>()
    private val playersByEmail = ConcurrentHashMap<String, WebPlayer>()
    private val playersById = ConcurrentHashMap<String, WebPlayer>()
    private val sessions = ConcurrentHashMap<String, Session>()

    override fun startChallenge(
        challenge: WebLoginChallenge,
        resendCooldownSeconds: Long,
        now: Instant,
    ): ChallengeStartResult {
        val key = normalizeEmail(challenge.email)
        val existing = challenges[key]
        if (existing?.usedAt == null &&
            existing
                ?.challenge
                ?.createdAt
                ?.plusSeconds(resendCooldownSeconds)
                ?.isAfter(now) == true
        ) {
            return ChallengeStartResult.Cooldown
        }
        challenges[key] = Challenge(challenge)
        return ChallengeStartResult.Sent
    }

    override fun consumeChallenge(
        email: String,
        codeHash: String,
        now: Instant,
    ): ChallengeConsumeResult {
        val key = normalizeEmail(email)
        val current = challenges[key] ?: return ChallengeConsumeResult.InvalidOrExpired
        if (current.challenge.expiresAt.isBefore(now) || current.usedAt != null) return ChallengeConsumeResult.InvalidOrExpired
        if (current.attemptCount >= MAX_AUTH_ATTEMPTS) return ChallengeConsumeResult.TooManyAttempts
        if (!MessageDigest.isEqual(current.challenge.codeHash.toByteArray(), codeHash.toByteArray())) {
            challenges[key] = current.copy(attemptCount = current.attemptCount + 1)
            return ChallengeConsumeResult.InvalidOrExpired
        }
        challenges[key] = current.copy(usedAt = now)
        return ChallengeConsumeResult.Accepted
    }

    override fun findOrCreatePlayer(email: String): WebPlayer {
        val normalized = normalizeEmail(email)
        return playersByEmail.computeIfAbsent(normalized) {
            WebPlayer(playerId = UUID.randomUUID().toString(), email = normalized).also { playersById[it.playerId] = it }
        }
    }

    override fun saveSession(session: WebSession) {
        sessions[session.tokenHash] = Session(session)
    }

    override fun findSession(
        tokenHash: String,
        now: Instant,
    ): WebPlayer? {
        val session = sessions[tokenHash] ?: return null
        if (session.revokedAt != null ||
            session.session.idleExpiresAt.isBefore(now) ||
            session.session.absoluteExpiresAt.isBefore(now)
        ) {
            return null
        }
        val refreshed =
            session.session.copy(
                lastSeenAt = now,
                idleExpiresAt = minOf(now.plusSeconds(SESSION_IDLE_SECONDS), session.session.absoluteExpiresAt),
            )
        sessions[tokenHash] = session.copy(session = refreshed)
        return playersById[session.session.playerId]
    }

    override fun revokeSession(
        tokenHash: String,
        now: Instant,
    ) {
        sessions.computeIfPresent(tokenHash) { _, session -> session.copy(revokedAt = now) }
    }
}

class WebAuthService(
    private val store: WebAuthStore,
    private val emailSender: EmailSender,
    private val secret: String = DEV_WEB_AUTH_SECRET,
    private val rateLimiter: RequestRateLimiter = NoopRateLimiter,
    private val rateLimitConfig: AuthRateLimitConfig = AuthRateLimitConfig.disabled(),
    private val fixedLoginCode: String? = null,
) {
    private val random = SecureRandom()

    suspend fun requestCode(
        email: String,
        ip: String? = null,
        userAgent: String? = null,
    ): StartLoginResult {
        if (!allow("login:${normalizeEmail(email)}:${ip.orEmpty()}")) return StartLoginResult.RateLimited
        val normalized = normalizeEmail(email)
        val code = fixedLoginCode?.takeIf { Regex("^[0-9]{6}$").matches(it) } ?: (random.nextInt(900_000) + 100_000).toString()
        val now = Instant.now()
        val id = UUID.randomUUID().toString()
        val challenge =
            WebLoginChallenge(
                id = id,
                email = normalized,
                codeHash = hashLoginCode(normalized, code),
                expiresAt = now.plusSeconds(LOGIN_CODE_MINUTES * 60L),
                createdAt = now,
                requestIpHash = ip?.let(::hashOpaque),
                userAgent = userAgent,
            )
        when (store.startChallenge(challenge, RESEND_COOLDOWN_SECONDS, now)) {
            ChallengeStartResult.Cooldown -> return StartLoginResult.Cooldown
            ChallengeStartResult.Sent -> Unit
        }
        return try {
            emailSender.sendLoginCode(LoginCodeEmail(normalized, code, LOGIN_CODE_MINUTES, "web-login/$id"))
            StartLoginResult.Sent
        } catch (_: Exception) {
            store.consumeChallenge(normalized, hashLoginCode(normalized, code), Instant.now())
            StartLoginResult.EmailSendFailed
        }
    }

    fun verify(
        email: String,
        code: String,
        ip: String? = null,
        userAgent: String? = null,
    ): VerifyLoginResult {
        if (!allow("verify:${normalizeEmail(email)}:${ip.orEmpty()}")) return VerifyLoginResult.RateLimited
        val normalized = normalizeEmail(email)
        if (!Regex("^[0-9]{6}$").matches(code.trim())) return VerifyLoginResult.InvalidOrExpired
        when (store.consumeChallenge(normalized, hashLoginCode(normalized, code.trim()), Instant.now())) {
            ChallengeConsumeResult.Accepted -> Unit
            ChallengeConsumeResult.InvalidOrExpired -> return VerifyLoginResult.InvalidOrExpired
            ChallengeConsumeResult.TooManyAttempts -> return VerifyLoginResult.TooManyAttempts
        }
        val player = store.findOrCreatePlayer(normalized)
        val token = generateToken()
        val now = Instant.now()
        store.saveSession(
            WebSession(
                id = UUID.randomUUID().toString(),
                playerId = player.playerId,
                tokenHash = hashSessionToken(token),
                createdAt = now,
                lastSeenAt = now,
                idleExpiresAt = now.plusSeconds(SESSION_IDLE_SECONDS),
                absoluteExpiresAt = now.plusSeconds(SESSION_ABSOLUTE_SECONDS),
                ipHash = ip?.let(::hashOpaque),
                userAgent = userAgent,
            ),
        )
        return VerifyLoginResult.Success(token, player)
    }

    fun validate(token: String?): WebPlayer? =
        token?.takeIf { it.isNotBlank() }?.let { store.findSession(hashSessionToken(it), Instant.now()) }

    fun logout(token: String) {
        token.takeIf { it.isNotBlank() }?.let { store.revokeSession(hashSessionToken(it), Instant.now()) }
    }

    private fun allow(key: String): Boolean =
        !rateLimitConfig.enabled || rateLimiter.check(key, rateLimitConfig.loginLimit, rateLimitConfig.loginWindowMs)

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashLoginCode(
        email: String,
        code: String,
    ): String = hmacSha256("$email:$code")

    private fun hashSessionToken(token: String): String = hmacSha256("session:$token")

    private fun hashOpaque(value: String): String = hmacSha256("opaque:$value")

    private fun hmacSha256(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

fun normalizeEmail(value: String): String = value.trim().lowercase()

private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")
