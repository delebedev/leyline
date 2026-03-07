package leyline.account

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID

/**
 * SQLite-backed account storage. Lives in the same DB as the player/deck tables
 * but owns only the `accounts` table.
 */
class AccountStore(private val database: Database) {

    internal object Accounts : Table("accounts") {
        val accountId = text("account_id")
        val personaId = text("persona_id").uniqueIndex()
        val email = text("email").uniqueIndex()
        val displayName = text("display_name")
        val passwordHash = text("password_hash")
        val country = text("country").default("US")
        val dob = text("dob")
        val createdAt = text("created_at")
        override val primaryKey = PrimaryKey(accountId)
    }

    fun createTables() {
        transaction(database) { SchemaUtils.create(Accounts) }
    }

    /** Register a new account. Returns the created [Account] or throws on duplicate email. */
    fun create(
        email: String,
        password: String,
        displayName: String,
        country: String = "US",
        dob: String = "1990-01-01",
    ): Account {
        val accountId = generateId()
        val personaId = generateId()
        val hash = BCrypt.hashpw(password, BCrypt.gensalt())
        val discriminator = (1..99999).random()
        val fullName = "$displayName#${discriminator.toString().padStart(5, '0')}"

        val now = java.time.Instant.now().toString()
        transaction(database) {
            Accounts.insert {
                it[Accounts.accountId] = accountId
                it[Accounts.personaId] = personaId
                it[Accounts.email] = email.lowercase()
                it[Accounts.displayName] = fullName
                it[Accounts.passwordHash] = hash
                it[Accounts.country] = country
                it[Accounts.dob] = dob
                it[Accounts.createdAt] = now
            }
        }
        return Account(accountId, personaId, email.lowercase(), fullName, country, dob, now)
    }

    /**
     * Insert an account with pre-determined IDs (used for dev seed).
     * Skips if an account with this email already exists.
     * Returns true if inserted, false if skipped.
     */
    fun seed(
        accountId: String,
        personaId: String,
        email: String,
        displayName: String,
        password: String,
        country: String = "US",
        dob: String = "1990-01-01",
    ): Boolean {
        val existing = findByEmail(email)
        if (existing != null) return false
        val hash = BCrypt.hashpw(password, BCrypt.gensalt())
        val now = java.time.Instant.now().toString()
        transaction(database) {
            Accounts.insert {
                it[Accounts.accountId] = accountId
                it[Accounts.personaId] = personaId
                it[Accounts.email] = email.lowercase()
                it[Accounts.displayName] = displayName
                it[Accounts.passwordHash] = hash
                it[Accounts.country] = country
                it[Accounts.dob] = dob
                it[Accounts.createdAt] = now
            }
        }
        return true
    }

    /** Authenticate by email + password. Returns [Account] on success, null on failure. */
    fun authenticate(email: String, password: String): Account? {
        val row = transaction(database) {
            Accounts.selectAll()
                .where { Accounts.email eq email.lowercase() }
                .firstOrNull()
        } ?: return null
        val hash = row[Accounts.passwordHash]
        if (!BCrypt.checkpw(password, hash)) return null
        return row.toAccount()
    }

    /** Look up account by email. */
    fun findByEmail(email: String): Account? = findOneBy(Accounts.email, email.lowercase())

    /** Look up account by persona ID (the JWT subject). */
    fun findByPersonaId(personaId: String): Account? = findOneBy(Accounts.personaId, personaId)

    /** Look up account by account ID. */
    fun findByAccountId(accountId: String): Account? = findOneBy(Accounts.accountId, accountId)

    private fun <T : Comparable<T>> findOneBy(column: org.jetbrains.exposed.v1.core.Column<T>, value: T): Account? =
        transaction(database) {
            Accounts.selectAll()
                .where { column eq value }
                .firstOrNull()
                ?.toAccount()
        }

    /** Check if any accounts exist (for dev seed logic). */
    fun isEmpty(): Boolean = transaction(database) {
        Accounts.selectAll().count() == 0L
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toAccount() = Account(
        accountId = this[Accounts.accountId],
        personaId = this[Accounts.personaId],
        email = this[Accounts.email],
        displayName = this[Accounts.displayName],
        country = this[Accounts.country],
        dob = this[Accounts.dob],
        createdAt = this[Accounts.createdAt],
    )

    private fun generateId(): String =
        UUID.randomUUID().toString().replace("-", "").uppercase().take(26)
}

/** Immutable account snapshot returned from store queries. */
data class Account(
    val accountId: String,
    val personaId: String,
    val email: String,
    val displayName: String,
    val country: String,
    val dob: String,
    val createdAt: String,
)
