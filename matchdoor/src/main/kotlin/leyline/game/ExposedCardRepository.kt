package leyline.game

import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only [CardRepository] over the client's local SQLite card DB (Exposed).
 *
 * Never creates or modifies the schema — tables (Cards, Localizations_enUS)
 * are owned by the Arena client. Entries are cached lazily per-key on first
 * access and never evicted; card data is immutable for a given client build.
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
        val expansionCode = text("ExpansionCode").default("")
        override val primaryKey = PrimaryKey(grpId)
    }

    private object Localizations : Table("Localizations_enUS") {
        val locId = integer("LocId")
        val formatted = integer("Formatted").default(0)
        val loc = text("Loc").default("")
        override val primaryKey = PrimaryKey(locId)
    }

    private object Abilities : Table("Abilities") {
        val id = integer("Id")
        val modalChildIds = text("ModalChildIds").default("")
        override val primaryKey = PrimaryKey(id)
    }

    /** Strip HTML formatting tags (e.g. `<nobr>`) from localized card names. */
    private fun stripTags(name: String): String = name.replace(tagRegex, "")
    private val tagRegex = Regex("</?[a-zA-Z][^>]*>")

    // --- In-memory caches ---

    private val dataCache = ConcurrentHashMap<Int, CardData?>()
    private val grpIdToName = ConcurrentHashMap<Int, String>()
    private val nameToGrpId = ConcurrentHashMap<String, Int>()
    private val modalCache = ConcurrentHashMap<Int, ModalAbilityInfo?>()

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

    override fun findGrpIdByNameAndSet(name: String, setCode: String): Int? = queryGrpIdByNameAndSet(name, setCode)?.also { grpId ->
        nameToGrpId[name] = grpId
        grpIdToName[grpId] = name
    }

    override fun findAllGrpIds(): List<Int> = try {
        transaction(database) {
            Cards.selectAll()
                .where { (Cards.isToken eq 0) and (Cards.isPrimaryCard eq 1) }
                .map { it[Cards.grpId] }
        }
    } catch (e: Exception) {
        log.warn("Failed to query all grpIds: {}", e.message)
        emptyList()
    }

    override fun lookupModalOptions(cardGrpId: Int): ModalAbilityInfo? {
        modalCache[cardGrpId]?.let { return it }
        val card = findByGrpId(cardGrpId) ?: return null
        if (card.abilityIds.isEmpty()) return null
        val info = queryModalOptions(card.abilityIds.map { it.first })
        modalCache[cardGrpId] = info
        return info
    }

    override fun registerModalOptions(cardGrpId: Int, info: ModalAbilityInfo) {
        modalCache[cardGrpId] = info
    }

    private fun queryModalOptions(abilityGrpIds: List<Int>): ModalAbilityInfo? = try {
        transaction(database) {
            for (abilityId in abilityGrpIds) {
                val row = Abilities.selectAll().where { Abilities.id eq abilityId }.firstOrNull() ?: continue
                val modalChildren = row[Abilities.modalChildIds]
                if (modalChildren.isBlank()) continue
                val childIds = modalChildren.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (childIds.isNotEmpty()) {
                    return@transaction ModalAbilityInfo(parentGrpId = abilityId, childGrpIds = childIds)
                }
            }
            null
        }
    } catch (e: Exception) {
        log.warn("Failed to query modal options for abilities: {}", e.message)
        null
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
                ?.let(::stripTags)
        }
    } catch (e: Exception) {
        log.warn("Failed to query name for grpId={}: {}", grpId, e.message)
        null
    }

    /**
     * Match card name against Loc column, tolerating HTML tags like `<nobr>`.
     * Tries exact match first, falls back to stripping tags via SQL REPLACE.
     */
    private fun locMatches(cardName: String) =
        (Localizations.loc eq cardName) or
            (
                CustomFunction<String>(
                    "REPLACE",
                    TextColumnType(),
                    CustomFunction<String>(
                        "REPLACE",
                        TextColumnType(),
                        Localizations.loc,
                        stringLiteral("<nobr>"),
                        stringLiteral(""),
                    ),
                    stringLiteral("</nobr>"),
                    stringLiteral(""),
                ) eq cardName
                )

    private fun queryGrpIdByNameAndSet(cardName: String, setCode: String): Int? = try {
        transaction(database) {
            Cards.join(Localizations, JoinType.INNER, Cards.titleId, Localizations.locId)
                .selectAll()
                .where {
                    (Localizations.formatted eq 1) and
                        locMatches(cardName) and
                        (Cards.expansionCode eq setCode) and
                        (Cards.isToken eq 0) and
                        (Cards.isPrimaryCard eq 1)
                }
                .firstOrNull()
                ?.get(Cards.grpId)
        }
    } catch (e: Exception) {
        log.warn("Failed to query grpId for name='{}' set='{}': {}", cardName, setCode, e.message)
        null
    }

    private fun queryGrpIdByName(cardName: String): Int? = try {
        transaction(database) {
            Cards.join(Localizations, JoinType.INNER, Cards.titleId, Localizations.locId)
                .selectAll()
                .where {
                    (Localizations.formatted eq 1) and
                        locMatches(cardName) and
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
