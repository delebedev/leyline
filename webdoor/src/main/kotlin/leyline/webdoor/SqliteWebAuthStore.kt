package leyline.webdoor

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class SqliteWebAuthStore(
    private val database: Database,
) : WebAuthStore {
    object Players : Table("web_players") {
        val playerId = text("player_id")
        val email = text("email").uniqueIndex()
        val createdAt = text("created_at")
        override val primaryKey = PrimaryKey(playerId)
    }

    object LoginChallenges : Table("web_login_challenges") {
        val id = text("id")
        val email = text("email").index()
        val codeHash = text("code_hash")
        val expiresAt = text("expires_at")
        val createdAt = text("created_at")
        val usedAt = text("used_at").nullable()
        val attemptCount = integer("attempt_count").default(0)
        val requestIpHash = text("request_ip_hash").nullable()
        val userAgent = text("user_agent").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    object Sessions : Table("web_sessions") {
        val id = text("id")
        val playerId = text("player_id").index()
        val tokenHash = text("token_hash").uniqueIndex()
        val createdAt = text("created_at")
        val lastSeenAt = text("last_seen_at")
        val idleExpiresAt = text("idle_expires_at")
        val absoluteExpiresAt = text("absolute_expires_at")
        val revokedAt = text("revoked_at").nullable()
        val ipHash = text("ip_hash").nullable()
        val userAgent = text("user_agent").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    fun createTables() {
        transaction(database) { SchemaUtils.create(Players, LoginChallenges, Sessions) }
    }

    override fun startChallenge(
        challenge: WebLoginChallenge,
        resendCooldownSeconds: Long,
        now: Instant,
    ): ChallengeStartResult =
        transaction(database) {
            cleanupExpiredAuthData(now)
            val latestUnused =
                LoginChallenges
                    .selectAll()
                    .where { (LoginChallenges.email eq challenge.email) and LoginChallenges.usedAt.isNull() }
                    .orderBy(LoginChallenges.createdAt, SortOrder.DESC)
                    .firstOrNull()
            if (latestUnused != null &&
                Instant.parse(latestUnused[LoginChallenges.createdAt]).plusSeconds(resendCooldownSeconds).isAfter(now)
            ) {
                return@transaction ChallengeStartResult.Cooldown
            }
            if (latestUnused != null) {
                LoginChallenges.update({ (LoginChallenges.email eq challenge.email) and LoginChallenges.usedAt.isNull() }) {
                    it[usedAt] = now.toString()
                }
            }
            LoginChallenges.insert {
                it[id] = challenge.id
                it[email] = challenge.email
                it[codeHash] = challenge.codeHash
                it[expiresAt] = challenge.expiresAt.toString()
                it[createdAt] = challenge.createdAt.toString()
                it[requestIpHash] = challenge.requestIpHash
                it[userAgent] = challenge.userAgent?.take(512)
            }
            ChallengeStartResult.Sent
        }

    override fun consumeChallenge(
        email: String,
        codeHash: String,
        now: Instant,
    ): ChallengeConsumeResult =
        transaction(database) {
            val challenge =
                LoginChallenges
                    .selectAll()
                    .where { (LoginChallenges.email eq email) and LoginChallenges.usedAt.isNull() }
                    .orderBy(LoginChallenges.createdAt, SortOrder.DESC)
                    .firstOrNull()
                    ?: return@transaction ChallengeConsumeResult.InvalidOrExpired
            if (Instant.parse(challenge[LoginChallenges.expiresAt]).isBefore(now)) {
                return@transaction ChallengeConsumeResult.InvalidOrExpired
            }
            if (challenge[LoginChallenges.attemptCount] >= MAX_AUTH_ATTEMPTS) return@transaction ChallengeConsumeResult.TooManyAttempts
            if (!MessageDigest.isEqual(challenge[LoginChallenges.codeHash].toByteArray(), codeHash.toByteArray())) {
                LoginChallenges.update({ LoginChallenges.id eq challenge[LoginChallenges.id] }) {
                    it[attemptCount] = challenge[LoginChallenges.attemptCount] + 1
                }
                return@transaction ChallengeConsumeResult.InvalidOrExpired
            }
            LoginChallenges.update({ LoginChallenges.id eq challenge[LoginChallenges.id] }) { it[usedAt] = now.toString() }
            ChallengeConsumeResult.Accepted
        }

    override fun findOrCreatePlayer(email: String): WebPlayer =
        transaction(database) {
            Players
                .selectAll()
                .where { Players.email eq email }
                .firstOrNull()
                ?.toWebPlayer()
                ?: WebPlayer(UUID.randomUUID().toString(), email).also { player ->
                    Players.insert {
                        it[playerId] = player.playerId
                        it[Players.email] = player.email
                        it[createdAt] = Instant.now().toString()
                    }
                }
        }

    override fun saveSession(session: WebSession) {
        transaction(database) {
            Sessions.insert {
                it[id] = session.id
                it[playerId] = session.playerId
                it[tokenHash] = session.tokenHash
                it[createdAt] = session.createdAt.toString()
                it[lastSeenAt] = session.lastSeenAt.toString()
                it[idleExpiresAt] = session.idleExpiresAt.toString()
                it[absoluteExpiresAt] = session.absoluteExpiresAt.toString()
                it[ipHash] = session.ipHash
                it[userAgent] = session.userAgent?.take(512)
            }
        }
    }

    override fun findSession(
        tokenHash: String,
        now: Instant,
    ): WebPlayer? =
        transaction(database) {
            val session = Sessions.selectAll().where { Sessions.tokenHash eq tokenHash }.firstOrNull() ?: return@transaction null
            if (session[Sessions.revokedAt] != null) return@transaction null
            if (Instant.parse(session[Sessions.idleExpiresAt]).isBefore(now)) return@transaction null
            if (Instant.parse(session[Sessions.absoluteExpiresAt]).isBefore(now)) return@transaction null
            val absoluteExpiry = Instant.parse(session[Sessions.absoluteExpiresAt])
            Sessions.update({ Sessions.id eq session[Sessions.id] }) {
                it[lastSeenAt] = now.toString()
                it[idleExpiresAt] = minOf(now.plusSeconds(SESSION_IDLE_SECONDS), absoluteExpiry).toString()
            }
            Players
                .selectAll()
                .where { Players.playerId eq session[Sessions.playerId] }
                .firstOrNull()
                ?.toWebPlayer()
        }

    override fun revokeSession(
        tokenHash: String,
        now: Instant,
    ) {
        transaction(database) {
            Sessions.update({ Sessions.tokenHash eq tokenHash }) { it[revokedAt] = now.toString() }
        }
    }

    private fun ResultRow.toWebPlayer() = WebPlayer(this[Players.playerId], this[Players.email])

    private fun cleanupExpiredAuthData(now: Instant) {
        val nowText = now.toString()
        LoginChallenges.deleteWhere { expiresAt less nowText }
        Sessions.deleteWhere { (idleExpiresAt less nowText) or (absoluteExpiresAt less nowText) }
    }
}
