package leyline.frontdoor.repo

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import leyline.frontdoor.domain.CollationPool
import leyline.frontdoor.domain.Course
import leyline.frontdoor.domain.CourseDeck
import leyline.frontdoor.domain.CourseDeckSummary
import leyline.frontdoor.domain.CourseId
import leyline.frontdoor.domain.CourseModule
import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.Format
import leyline.frontdoor.domain.Player
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.domain.Preferences
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Exposed DSL implementation over the player SQLite schema.
 * Implements both [DeckRepository] and [PlayerRepository].
 */
class SqlitePlayerStore(private val database: Database) :
    DeckRepository,
    PlayerRepository,
    CourseRepository {

    /* ---------- Exposed table objects (match existing schema exactly) ---------- */

    private object Players : Table("players") {
        val playerId = text("player_id")
        val screenName = text("screen_name")
        val preferences = text("preferences").default("{}")
        val inventory = text("inventory").default("{}")
        val cosmetics = text("cosmetics").default("{}")
        val rankInfo = text("rank_info").default("{}")
        val createdAt = text("created_at").default("datetime('now')")
        override val primaryKey = PrimaryKey(playerId)
    }

    private object Decks : Table("decks") {
        val deckId = text("deck_id")
        val playerId = text("player_id")
        val name = text("name")
        val tileId = integer("tile_id").default(0)
        val format = text("format").default("Standard")
        val isFavorite = bool("is_favorite").default(false)
        val cards = text("cards").default("{}")
        val updatedAt = text("updated_at").default("datetime('now')")
        override val primaryKey = PrimaryKey(deckId)
    }

    private object Courses : Table("courses") {
        val id = text("id")
        val playerId = text("player_id")
        val eventName = text("event_name")
        val module = text("module")
        val wins = integer("wins").default(0)
        val losses = integer("losses").default(0)
        val cardPool = text("card_pool").default("[]")
        val cardPoolByCollation = text("card_pool_by_collation").default("[]")
        val deck = text("deck").nullable()
        val deckSummary = text("deck_summary").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    /* ---------- JSON wire format for the cards column ---------- */

    @Serializable
    private data class CardEntry(val cardId: Int, val quantity: Int)

    @Serializable
    private data class CollationPoolDto(val collationId: Int, val cardPool: List<Int>)

    @Serializable
    private data class CourseDeckDto(
        val deckId: String,
        val mainDeck: List<CardEntry>,
        val sideboard: List<CardEntry>,
    )

    @Serializable
    private data class CourseDeckSummaryDto(
        val deckId: String,
        val name: String,
        val tileId: Int,
        val format: String,
    )

    @Serializable
    private data class CardsBlob(
        val MainDeck: List<CardEntry> = emptyList(),
        val Sideboard: List<CardEntry> = emptyList(),
        val CommandZone: List<CardEntry> = emptyList(),
        val Companions: List<CardEntry> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /* ---------- Schema bootstrap ---------- */

    fun createTables() {
        transaction(database) { SchemaUtils.create(Players, Decks, Courses) }
    }

    /* ---------- DeckRepository ---------- */

    override fun findById(id: DeckId): Deck? = transaction(database) {
        Decks.selectAll().where { Decks.deckId eq id.value }.firstOrNull()?.toDeck()
    }

    override fun findByName(name: String): Deck? = transaction(database) {
        Decks.selectAll().where { Decks.name eq name }.firstOrNull()?.toDeck()
    }

    override fun findAllForPlayer(playerId: PlayerId): List<Deck> = transaction(database) {
        Decks.selectAll()
            .where { Decks.playerId eq playerId.value }
            .map { it.toDeck() }
    }

    override fun save(deck: Deck) {
        transaction(database) {
            val exists = Decks.selectAll()
                .where { Decks.deckId eq deck.id.value }
                .count() > 0
            if (exists) {
                Decks.update({ Decks.deckId eq deck.id.value }) {
                    it[Decks.name] = deck.name
                    it[Decks.tileId] = deck.tileId
                    it[Decks.format] = deck.format.name
                    it[Decks.isFavorite] = deck.isFavorite
                    it[Decks.cards] = encodeCards(deck)
                    it[Decks.playerId] = deck.playerId.value
                }
            } else {
                Decks.insert {
                    it[Decks.deckId] = deck.id.value
                    it[Decks.playerId] = deck.playerId.value
                    it[Decks.name] = deck.name
                    it[Decks.tileId] = deck.tileId
                    it[Decks.format] = deck.format.name
                    it[Decks.isFavorite] = deck.isFavorite
                    it[Decks.cards] = encodeCards(deck)
                }
            }
        }
    }

    override fun delete(id: DeckId) {
        transaction(database) { Decks.deleteWhere { deckId eq id.value } }
    }

    /* ---------- PlayerRepository ---------- */

    override fun findPlayer(id: PlayerId): Player? = transaction(database) {
        Players.selectAll()
            .where { Players.playerId eq id.value }
            .firstOrNull()
            ?.let {
                Player(
                    id = PlayerId(it[Players.playerId]),
                    screenName = it[Players.screenName],
                )
            }
    }

    override fun getPreferences(id: PlayerId): Preferences? = transaction(database) {
        Players.selectAll()
            .where { Players.playerId eq id.value }
            .firstOrNull()
            ?.let { Preferences(it[Players.preferences]) }
    }

    override fun savePreferences(id: PlayerId, prefs: Preferences) {
        transaction(database) {
            Players.update({ Players.playerId eq id.value }) {
                it[Players.preferences] = prefs.json
            }
        }
    }

    override fun ensurePlayer(id: PlayerId, screenName: String) {
        transaction(database) {
            val exists = Players.selectAll()
                .where { Players.playerId eq id.value }
                .count() > 0
            if (!exists) {
                Players.insert {
                    it[Players.playerId] = id.value
                    it[Players.screenName] = screenName
                }
            }
        }
    }

    /* ---------- CourseRepository ---------- */

    override fun findById(id: CourseId): Course? = transaction(database) {
        Courses.selectAll().where { Courses.id eq id.value }.firstOrNull()?.toCourse()
    }

    override fun findByPlayer(playerId: PlayerId): List<Course> = transaction(database) {
        Courses.selectAll().where { Courses.playerId eq playerId.value }.map { it.toCourse() }
    }

    override fun findByPlayerAndEvent(playerId: PlayerId, eventName: String): Course? =
        transaction(database) {
            Courses.selectAll().where {
                (Courses.playerId eq playerId.value) and (Courses.eventName eq eventName)
            }.firstOrNull()?.toCourse()
        }

    override fun save(course: Course) {
        transaction(database) {
            val existing = Courses.selectAll().where { Courses.id eq course.id.value }.count() > 0
            val poolJson = json.encodeToString(course.cardPool)
            val collationJson = json.encodeToString(
                course.cardPoolByCollation.map { CollationPoolDto(it.collationId, it.cardPool) },
            )
            val deckJson = course.deck?.let { d ->
                json.encodeToString(
                    CourseDeckDto(
                        d.deckId.value,
                        d.mainDeck.map { CardEntry(it.grpId, it.quantity) },
                        d.sideboard.map { CardEntry(it.grpId, it.quantity) },
                    ),
                )
            }
            val summaryJson = course.deckSummary?.let { s ->
                json.encodeToString(
                    CourseDeckSummaryDto(s.deckId.value, s.name, s.tileId, s.format),
                )
            }
            if (existing) {
                Courses.update({ Courses.id eq course.id.value }) {
                    it[module] = course.module.name
                    it[wins] = course.wins
                    it[losses] = course.losses
                    it[cardPool] = poolJson
                    it[cardPoolByCollation] = collationJson
                    it[deck] = deckJson
                    it[deckSummary] = summaryJson
                }
            } else {
                Courses.insert {
                    it[id] = course.id.value
                    it[playerId] = course.playerId.value
                    it[eventName] = course.eventName
                    it[module] = course.module.name
                    it[wins] = course.wins
                    it[losses] = course.losses
                    it[cardPool] = poolJson
                    it[cardPoolByCollation] = collationJson
                    it[deck] = deckJson
                    it[deckSummary] = summaryJson
                }
            }
        }
    }

    override fun delete(id: CourseId) {
        transaction(database) { Courses.deleteWhere { Courses.id eq id.value } }
    }

    /* ---------- Mapping helpers ---------- */

    private fun ResultRow.toDeck(): Deck {
        val blob = json.decodeFromString<CardsBlob>(this[Decks.cards])
        return Deck(
            id = DeckId(this[Decks.deckId]),
            playerId = PlayerId(this[Decks.playerId]),
            name = this[Decks.name],
            format = Format.fromString(this[Decks.format]),
            tileId = this[Decks.tileId],
            mainDeck = blob.MainDeck.map { DeckCard(it.cardId, it.quantity) },
            sideboard = blob.Sideboard.map { DeckCard(it.cardId, it.quantity) },
            commandZone = blob.CommandZone.map { DeckCard(it.cardId, it.quantity) },
            companions = blob.Companions.map { DeckCard(it.cardId, it.quantity) },
            isFavorite = this[Decks.isFavorite],
        )
    }

    private fun ResultRow.toCourse(): Course {
        val poolJson = this[Courses.cardPool]
        val collationJson = this[Courses.cardPoolByCollation]
        val deckJson = this[Courses.deck]
        val summaryJson = this[Courses.deckSummary]

        return Course(
            id = CourseId(this[Courses.id]),
            playerId = PlayerId(this[Courses.playerId]),
            eventName = this[Courses.eventName],
            module = CourseModule.valueOf(this[Courses.module]),
            wins = this[Courses.wins],
            losses = this[Courses.losses],
            cardPool = json.decodeFromString(poolJson),
            cardPoolByCollation = json.decodeFromString<List<CollationPoolDto>>(collationJson)
                .map { CollationPool(it.collationId, it.cardPool) },
            deck = deckJson?.let { d ->
                val dto = json.decodeFromString<CourseDeckDto>(d)
                CourseDeck(
                    DeckId(dto.deckId),
                    dto.mainDeck.map { DeckCard(it.cardId, it.quantity) },
                    dto.sideboard.map { DeckCard(it.cardId, it.quantity) },
                )
            },
            deckSummary = summaryJson?.let { s ->
                val dto = json.decodeFromString<CourseDeckSummaryDto>(s)
                CourseDeckSummary(DeckId(dto.deckId), dto.name, dto.tileId, dto.format)
            },
        )
    }

    private fun encodeCards(deck: Deck): String {
        val blob = CardsBlob(
            MainDeck = deck.mainDeck.map { CardEntry(it.grpId, it.quantity) },
            Sideboard = deck.sideboard.map { CardEntry(it.grpId, it.quantity) },
            CommandZone = deck.commandZone.map { CardEntry(it.grpId, it.quantity) },
            Companions = deck.companions.map { CardEntry(it.grpId, it.quantity) },
        )
        return json.encodeToString(blob)
    }
}
