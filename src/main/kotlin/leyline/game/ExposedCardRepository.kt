package leyline.game

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * [CardRepository] backed by the client's local card database (SQLite via Exposed).
 *
 * Read-only — does NOT call SchemaUtils.create. The DB schema is the client's
 * external Cards + Localizations_enUS tables.
 *
 * Results are cached in memory (card data is immutable, DB is read-only).
 */
class ExposedCardRepository(private val database: Database) : CardRepository {

    private val log = LoggerFactory.getLogger(ExposedCardRepository::class.java)

    // --- Exposed table objects matching external schema ---

    private object Cards : Table("Cards") {
        val grpId = integer("GrpId")
        val titleId = integer("TitleId")
        val power = text("Power").default("")
        val toughness = text("Toughness").default("")
        val colors = text("Colors").default("")
        val types = text("Types").default("")
        val subtypes = text("Subtypes").default("")
        val supertypes = text("Supertypes").default("")
        val abilityIds = text("AbilityIds").default("")
        val oldSchoolManaText = text("OldSchoolManaText").default("")
        val abilityIdToLinkedTokenGrpId = text("AbilityIdToLinkedTokenGrpId").default("")
        val isToken = integer("IsToken").default(0)
        val isPrimaryCard = integer("IsPrimaryCard").default(1)
        val isDigitalOnly = integer("IsDigitalOnly").default(0)
        val isRebalanced = integer("IsRebalanced").default(0)
        override val primaryKey = PrimaryKey(grpId)
    }

    private object Localizations : Table("Localizations_enUS") {
        val locId = integer("LocId")
        val formatted = integer("Formatted").default(0)
        val loc = text("Loc").default("")
        override val primaryKey = PrimaryKey(locId)
    }

    // --- In-memory caches ---

    private val dataCache = mutableMapOf<Int, CardData?>()
    private val grpIdToName = mutableMapOf<Int, String>()
    private val nameToGrpId = mutableMapOf<String, Int>()

    // --- CardRepository ---

    override fun findByGrpId(grpId: Int): CardData? {
        dataCache[grpId]?.let { return it }
        val data = queryCardData(grpId)
        dataCache[grpId] = data
        // Also cache the name if we got data
        if (data != null) {
            queryNameByGrpId(grpId)
        }
        return data
    }

    override fun findNameByGrpId(grpId: Int): String? {
        grpIdToName[grpId]?.let { return it }
        return queryNameByGrpId(grpId)?.also { name ->
            grpIdToName[grpId] = name
            nameToGrpId[name] = grpId
        }
    }

    override fun findGrpIdByName(name: String): Int? {
        nameToGrpId[name]?.let { return it }
        return queryGrpIdByName(name)?.also { grpId ->
            nameToGrpId[name] = grpId
            grpIdToName[grpId] = name
        }
    }

    override fun tokenGrpIdForCard(sourceGrpId: Int, tokenName: String?): Int? {
        val data = findByGrpId(sourceGrpId) ?: return null
        val tokens = data.tokenGrpIds
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) return tokens.values.first()
        if (tokenName == null) return null
        for ((_, tokenGrpId) in tokens) {
            val name = findNameByGrpId(tokenGrpId) ?: run {
                findByGrpId(tokenGrpId)
                findNameByGrpId(tokenGrpId)
            }
            if (name == tokenName) return tokenGrpId
        }
        return null
    }

    // --- Queries ---

    private fun queryCardData(grpId: Int): CardData? = try {
        transaction(database) {
            Cards.selectAll().where { Cards.grpId eq grpId }.firstOrNull()?.let { row ->
                CardData(
                    grpId = row[Cards.grpId],
                    titleId = row[Cards.titleId],
                    power = row[Cards.power],
                    toughness = row[Cards.toughness],
                    colors = parseIntList(row[Cards.colors]),
                    types = parseIntList(row[Cards.types]),
                    subtypes = parseIntList(row[Cards.subtypes]),
                    supertypes = parseIntList(row[Cards.supertypes]),
                    abilityIds = parseAbilityIds(row[Cards.abilityIds]),
                    manaCost = parseManaCost(row[Cards.oldSchoolManaText]),
                    tokenGrpIds = parseTokenGrpIds(row[Cards.abilityIdToLinkedTokenGrpId]),
                )
            }
        }
    } catch (e: Exception) {
        log.warn("Failed to query card DB for grpId={}: {}", grpId, e.message)
        null
    }

    private fun queryNameByGrpId(grpId: Int): String? = try {
        transaction(database) {
            // Join Cards with Localizations on TitleId=LocId, Formatted=1
            Cards.join(Localizations, JoinType.INNER, Cards.titleId, Localizations.locId)
                .selectAll()
                .where { (Cards.grpId eq grpId) and (Localizations.formatted eq 1) }
                .firstOrNull()
                ?.get(Localizations.loc)
        }
    } catch (e: Exception) {
        log.warn("Failed to query name for grpId={}: {}", grpId, e.message)
        null
    }

    private fun queryGrpIdByName(cardName: String): Int? = try {
        transaction(database) {
            Cards.join(Localizations, JoinType.INNER, Cards.titleId, Localizations.locId)
                .selectAll()
                .where {
                    (Localizations.formatted eq 1) and
                        (Localizations.loc eq cardName) and
                        (Cards.isToken eq 0) and
                        (Cards.isPrimaryCard eq 1)
                }
                .orderBy(Cards.isDigitalOnly)
                .orderBy(Cards.isRebalanced)
                .orderBy(Cards.grpId, order = org.jetbrains.exposed.v1.core.SortOrder.DESC)
                .firstOrNull()
                ?.get(Cards.grpId)
        }
    } catch (e: Exception) {
        log.warn("Failed to query grpId for name='{}': {}", cardName, e.message)
        null
    }
}
