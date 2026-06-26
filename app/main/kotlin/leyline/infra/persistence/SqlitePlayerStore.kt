package leyline.infra.persistence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import leyline.domain.CollationPool
import leyline.domain.Course
import leyline.domain.CourseDeck
import leyline.domain.CourseDeckSummary
import leyline.domain.CourseId
import leyline.domain.CourseModule
import leyline.domain.Deck
import leyline.domain.DeckCard
import leyline.domain.DeckId
import leyline.domain.DraftSession
import leyline.domain.DraftSessionId
import leyline.domain.DraftStatus
import leyline.domain.Format
import leyline.domain.Player
import leyline.domain.PlayerId
import leyline.domain.Preferences
import leyline.domain.repo.CourseRepository
import leyline.domain.repo.DeckRepository
import leyline.domain.repo.DraftSessionRepository
import leyline.domain.repo.PlayerRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
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
class SqlitePlayerStore(
    private val database: Database,
) : DeckRepository,
    PlayerRepository,
    CourseRepository {
    // ---------- Exposed table objects (match existing schema exactly) ----------

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

    private object DraftSessions : Table("draft_sessions") {
        val id = text("id")
        val playerId = text("player_id")
        val eventName = text("event_name")
        val status = text("status")
        val packNumber = integer("pack_number").default(0)
        val pickNumber = integer("pick_number").default(0)
        val draftPack = text("draft_pack").default("[]")
        val pickedCards = text("picked_cards").default("[]")
        override val primaryKey = PrimaryKey(id)
    }

    private object DraftPodResults : Table("draft_pod_results") {
        val sessionId = text("session_id")
        val seatIndex = integer("seat_index")
        val mainJson = text("main_json")
        override val primaryKey = PrimaryKey(sessionId, seatIndex)
    }

    // ---------- JSON wire format for the cards column ----------

    @Serializable
    private data class CardEntry(
        val cardId: Int,
        val quantity: Int,
    )

    @Serializable
    private data class CollationPoolDto(
        val collationId: Int,
        val cardPool: List<Int>,
    )

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
        val deckArtId: Int = 0,
        val preferredSleeve: String = "",
    )

    @Serializable
    private data class CardsBlob(
        @SerialName("MainDeck") val mainDeck: List<CardEntry> = emptyList(),
        @SerialName("Sideboard") val sideboard: List<CardEntry> = emptyList(),
        @SerialName("CommandZone") val commandZone: List<CardEntry> = emptyList(),
        @SerialName("Companions") val companions: List<CardEntry> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    // ---------- Schema bootstrap ----------

    fun createTables() {
        transaction(database) {
            SchemaUtils.create(Players, Decks, Courses, DraftSessions, DraftPodResults)
        }
    }

    // ---------- DeckRepository ----------

    override fun findById(id: DeckId): Deck? =
        transaction(database) {
            Decks
                .selectAll()
                .where { Decks.deckId eq id.value }
                .firstOrNull()
                ?.toDeck()
        }

    override fun findByName(name: String): Deck? =
        transaction(database) {
            Decks
                .selectAll()
                .where { Decks.name eq name }
                .firstOrNull()
                ?.toDeck()
        }

    override fun findAllForPlayer(playerId: PlayerId): List<Deck> =
        transaction(database) {
            Decks
                .selectAll()
                .where { Decks.playerId eq playerId.value }
                .map { it.toDeck() }
        }

    override fun save(deck: Deck) {
        transaction(database) {
            val exists =
                Decks
                    .selectAll()
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

    // ---------- PlayerRepository ----------

    override fun findPlayer(id: PlayerId): Player? =
        transaction(database) {
            Players
                .selectAll()
                .where { Players.playerId eq id.value }
                .firstOrNull()
                ?.let {
                    Player(
                        id = PlayerId(it[Players.playerId]),
                        screenName = it[Players.screenName],
                    )
                }
        }

    override fun getPreferences(id: PlayerId): Preferences? =
        transaction(database) {
            Players
                .selectAll()
                .where { Players.playerId eq id.value }
                .firstOrNull()
                ?.let { Preferences(it[Players.preferences]) }
        }

    override fun savePreferences(
        id: PlayerId,
        prefs: Preferences,
    ) {
        transaction(database) {
            Players.update({ Players.playerId eq id.value }) {
                it[Players.preferences] = prefs.json
            }
        }
    }

    override fun ensurePlayer(
        id: PlayerId,
        screenName: String,
    ) {
        transaction(database) {
            val exists =
                Players
                    .selectAll()
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

    // ---------- CourseRepository ----------

    override fun findById(id: CourseId): Course? =
        transaction(database) {
            Courses
                .selectAll()
                .where { Courses.id eq id.value }
                .firstOrNull()
                ?.toCourse()
        }

    override fun findByPlayer(playerId: PlayerId): List<Course> =
        transaction(database) {
            Courses.selectAll().where { Courses.playerId eq playerId.value }.map { it.toCourse() }
        }

    override fun findByPlayerAndEvent(
        playerId: PlayerId,
        eventName: String,
    ): Course? =
        transaction(database) {
            Courses
                .selectAll()
                .where {
                    (Courses.playerId eq playerId.value) and (Courses.eventName eq eventName)
                }.firstOrNull()
                ?.toCourse()
        }

    override fun save(course: Course) {
        transaction(database) {
            val existing = Courses.selectAll().where { Courses.id eq course.id.value }.count() > 0
            val poolJson = json.encodeToString(course.cardPool)
            val collationJson =
                json.encodeToString(
                    course.cardPoolByCollation.map { CollationPoolDto(it.collationId, it.cardPool) },
                )
            val deckJson =
                course.deck?.let { d ->
                    json.encodeToString(
                        CourseDeckDto(
                            d.deckId.value,
                            d.mainDeck.map { CardEntry(it.grpId, it.quantity) },
                            d.sideboard.map { CardEntry(it.grpId, it.quantity) },
                        ),
                    )
                }
            val summaryJson =
                course.deckSummary?.let { s ->
                    json.encodeToString(
                        CourseDeckSummaryDto(s.deckId.value, s.name, s.tileId, s.format, s.deckArtId, s.preferredSleeve),
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

    // ---------- DraftSessionRepository ----------

    fun findDraftByPlayerAndEvent(
        playerId: PlayerId,
        eventName: String,
    ): DraftSession? =
        transaction(database) {
            DraftSessions
                .selectAll()
                .where {
                    (DraftSessions.playerId eq playerId.value) and (DraftSessions.eventName eq eventName)
                }.firstOrNull()
                ?.toDraftSession()
        }

    fun findDraftById(id: DraftSessionId): DraftSession? =
        transaction(database) {
            DraftSessions
                .selectAll()
                .where { DraftSessions.id eq id.value }
                .firstOrNull()
                ?.toDraftSession()
        }

    fun saveDraft(session: DraftSession): Unit =
        transaction(database) {
            val draftPackJson = json.encodeToString<List<Int>>(session.draftPack)
            val pickedJson = json.encodeToString<List<Int>>(session.pickedCards)

            val exists =
                DraftSessions
                    .selectAll()
                    .where { DraftSessions.id eq session.id.value }
                    .count() > 0
            if (exists) {
                DraftSessions.update({ DraftSessions.id eq session.id.value }) {
                    it[status] = session.status.name
                    it[packNumber] = session.packNumber
                    it[pickNumber] = session.pickNumber
                    it[draftPack] = draftPackJson
                    it[pickedCards] = pickedJson
                }
            } else {
                DraftSessions.insert {
                    it[id] = session.id.value
                    it[playerId] = session.playerId.value
                    it[eventName] = session.eventName
                    it[status] = session.status.name
                    it[this.packNumber] = session.packNumber
                    it[this.pickNumber] = session.pickNumber
                    it[this.draftPack] = draftPackJson
                    it[this.pickedCards] = pickedJson
                }
            }
        }

    fun deleteDraft(id: DraftSessionId): Unit =
        transaction(database) {
            DraftPodResults.deleteWhere { sessionId eq id.value }
            DraftSessions.deleteWhere { DraftSessions.id eq id.value }
        }

    fun deleteIncompleteDrafts(): Unit =
        transaction(database) {
            val incompleteIds =
                DraftSessions
                    .selectAll()
                    .where { DraftSessions.status neq DraftStatus.Completed.name }
                    .map { it[DraftSessions.id] }
            if (incompleteIds.isNotEmpty()) {
                DraftPodResults.deleteWhere { sessionId inList incompleteIds }
                DraftSessions.deleteWhere { DraftSessions.id inList incompleteIds }
            }
        }

    fun savePodResults(
        sessionId: DraftSessionId,
        botDecks: List<List<Int>>,
    ): Unit =
        transaction(database) {
            DraftPodResults.deleteWhere { DraftPodResults.sessionId eq sessionId.value }
            botDecks.forEachIndexed { idx, deck ->
                DraftPodResults.insert {
                    it[DraftPodResults.sessionId] = sessionId.value
                    it[seatIndex] = idx
                    it[mainJson] = json.encodeToString<List<Int>>(deck)
                }
            }
        }

    fun findPodResults(sessionId: DraftSessionId): List<List<Int>> =
        transaction(database) {
            DraftPodResults
                .selectAll()
                .where { DraftPodResults.sessionId eq sessionId.value }
                .orderBy(DraftPodResults.seatIndex)
                .map { json.decodeFromString<List<Int>>(it[DraftPodResults.mainJson]) }
        }

    private fun ResultRow.toDraftSession(): DraftSession =
        DraftSession(
            id = DraftSessionId(this[DraftSessions.id]),
            playerId = PlayerId(this[DraftSessions.playerId]),
            eventName = this[DraftSessions.eventName],
            status = DraftStatus.valueOf(this[DraftSessions.status]),
            packNumber = this[DraftSessions.packNumber],
            pickNumber = this[DraftSessions.pickNumber],
            draftPack = json.decodeFromString<List<Int>>(this[DraftSessions.draftPack]),
            pickedCards = json.decodeFromString<List<Int>>(this[DraftSessions.pickedCards]),
        )

    fun asDraftSessionRepository(): DraftSessionRepository =
        object : DraftSessionRepository {
            override fun findById(id: DraftSessionId) = findDraftById(id)

            override fun findByPlayerAndEvent(
                playerId: PlayerId,
                eventName: String,
            ) = findDraftByPlayerAndEvent(playerId, eventName)

            override fun save(session: DraftSession) = saveDraft(session)

            override fun delete(id: DraftSessionId) = deleteDraft(id)

            override fun deleteIncomplete() = deleteIncompleteDrafts()

            override fun savePodResults(
                sessionId: DraftSessionId,
                botDecks: List<List<Int>>,
            ) = this@SqlitePlayerStore.savePodResults(sessionId, botDecks)

            override fun findPodResults(sessionId: DraftSessionId) = this@SqlitePlayerStore.findPodResults(sessionId)
        }

    // ---------- Mapping helpers ----------

    private fun ResultRow.toDeck(): Deck {
        val blob = json.decodeFromString<CardsBlob>(this[Decks.cards])
        return Deck(
            id = DeckId(this[Decks.deckId]),
            playerId = PlayerId(this[Decks.playerId]),
            name = this[Decks.name],
            format = Format.fromString(this[Decks.format]),
            tileId = this[Decks.tileId],
            mainDeck = blob.mainDeck.map { DeckCard(it.cardId, it.quantity) },
            sideboard = blob.sideboard.map { DeckCard(it.cardId, it.quantity) },
            commandZone = blob.commandZone.map { DeckCard(it.cardId, it.quantity) },
            companions = blob.companions.map { DeckCard(it.cardId, it.quantity) },
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
            cardPoolByCollation =
                json
                    .decodeFromString<List<CollationPoolDto>>(collationJson)
                    .map { CollationPool(it.collationId, it.cardPool) },
            deck =
                deckJson?.let { d ->
                    val dto = json.decodeFromString<CourseDeckDto>(d)
                    CourseDeck(
                        DeckId(dto.deckId),
                        dto.mainDeck.map { DeckCard(it.cardId, it.quantity) },
                        dto.sideboard.map { DeckCard(it.cardId, it.quantity) },
                    )
                },
            deckSummary =
                summaryJson?.let { s ->
                    val dto = json.decodeFromString<CourseDeckSummaryDto>(s)
                    CourseDeckSummary(DeckId(dto.deckId), dto.name, dto.tileId, dto.format, dto.deckArtId, dto.preferredSleeve)
                },
        )
    }

    private fun encodeCards(deck: Deck): String {
        val blob =
            CardsBlob(
                mainDeck = deck.mainDeck.map { CardEntry(it.grpId, it.quantity) },
                sideboard = deck.sideboard.map { CardEntry(it.grpId, it.quantity) },
                commandZone = deck.commandZone.map { CardEntry(it.grpId, it.quantity) },
                companions = deck.companions.map { CardEntry(it.grpId, it.quantity) },
            )
        return json.encodeToString(blob)
    }
}
