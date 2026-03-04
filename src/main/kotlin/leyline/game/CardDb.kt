package leyline.game

import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File
import java.sql.DriverManager

/**
 * Read-only lookup into the client's local card database (SQLite).
 *
 * DB enum values (CardColor, CardType, SubType) map 1:1 to proto enum values.
 * Path: ~/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga
 */
object CardDb {
    private val log = LoggerFactory.getLogger(CardDb::class.java)

    data class CardData(
        val grpId: Int,
        val titleId: Int,
        val power: String,
        val toughness: String,
        val colors: List<Int>, // proto CardColor values
        val types: List<Int>, // proto CardType values
        val subtypes: List<Int>, // proto SubType values
        val supertypes: List<Int>, // proto SuperType values
        val abilityIds: List<Pair<Int, Int>>, // abilityGrpId:textId pairs
        val manaCost: List<Pair<ManaColor, Int>>, // (color, count) from OldSchoolManaText
        val tokenGrpIds: Map<Int, Int> = emptyMap(), // abilityGrpId → tokenGrpId
    )

    private val cache = mutableMapOf<Int, CardData?>()
    private var dbPath: String? = null

    /**
     * When true, [lookupByName] and [lookup] never fall through to SQLite.
     * Set by test harnesses so tests run purely from in-memory registrations.
     */
    @Volatile
    var testMode: Boolean = false

    // In-memory grpId ↔ name table (for tests + manual registration)
    private val grpIdToName = mutableMapOf<Int, String>()
    private val nameToGrpId = mutableMapOf<String, Int>()
    val registeredCount: Int get() = grpIdToName.size

    fun getCardName(grpId: Int): String? = grpIdToName[grpId]
    fun getGrpId(cardName: String): Int? = nameToGrpId[cardName]

    /**
     * Look up grpId by card name. Checks in-memory cache first, then queries SQLite.
     * In [testMode], returns null immediately if name is not pre-registered.
     */
    fun lookupByName(cardName: String): Int? {
        nameToGrpId[cardName]?.let { return it }
        if (testMode) return null
        val path = dbPath ?: if (init()) dbPath else return null
        path ?: return null
        return queryByName(path, cardName)?.also { grpId ->
            nameToGrpId[cardName] = grpId
            grpIdToName[grpId] = cardName
        }
    }

    /** Manual registration for smoke tests. */
    fun register(grpId: Int, cardName: String) {
        grpIdToName[grpId] = cardName
        nameToGrpId[cardName] = grpId
    }

    /** Register card with full metadata (for tests without client SQLite DB). */
    fun registerData(data: CardData, cardName: String) {
        register(data.grpId, cardName)
        cache[data.grpId] = data
    }

    fun clear() {
        grpIdToName.clear()
        nameToGrpId.clear()
        cache.clear()
        testMode = false
    }

    /** Initialize from an explicit database file (e.g. Docker volume mount via LEYLINE_CARD_DB). */
    fun init(dbFile: File): Boolean {
        if (!dbFile.exists()) {
            log.warn("Card database not found at {}", dbFile.absolutePath)
            return false
        }
        dbPath = dbFile.absolutePath
        log.info("Card database: {} ({} MB)", dbFile.name, dbFile.length() / 1024 / 1024)
        return true
    }

    /** Auto-detect the client card database from the macOS Arena install. */
    fun init(): Boolean {
        val raw = File(System.getProperty("user.home"))
            .resolve("Library/Application Support/com.wizards.mtga/Downloads/Raw")
        val db = raw.listFiles()?.firstOrNull { it.name.startsWith("Raw_CardDatabase_") && it.name.endsWith(".mtga") }
        if (db == null) {
            log.warn("Client card database not found in {}", raw)
            return false
        }
        dbPath = db.absolutePath
        log.info("Card database: {} ({} MB)", db.name, db.length() / 1024 / 1024)
        return true
    }

    /**
     * Look up card data by grpId. Returns null if DB not available or grpId not found.
     * In [testMode], returns null immediately if grpId is not in the cache.
     */
    fun lookup(grpId: Int): CardData? {
        cache[grpId]?.let { return it }
        if (testMode) return null
        val path = dbPath ?: if (init()) dbPath else return null
        path ?: return null

        val data = query(path, grpId)
        cache[grpId] = data
        return data
    }

    /** Build a [GameObjectInfo] from DB data, no template — for buildFromGame path. */
    fun buildObjectInfo(grpId: Int): GameObjectInfo.Builder {
        val builder = GameObjectInfo.newBuilder()
            .setGrpId(grpId)
            .setOverlayGrpId(grpId)
        val card = lookup(grpId) ?: return builder
        builder.setName(card.titleId)
        card.types.forEach { builder.addCardTypes(CardType.forNumber(it) ?: return@forEach) }
        card.subtypes.forEach { builder.addSubtypes(SubType.forNumber(it) ?: return@forEach) }
        card.supertypes.forEach { builder.addSuperTypes(SuperType.forNumber(it) ?: return@forEach) }
        card.colors.forEach { builder.addColor(CardColor.forNumber(it) ?: return@forEach) }
        if (card.power.isNotEmpty()) builder.setPower(Int32Value.newBuilder().setValue(card.power.toIntOrNull() ?: 0))
        if (card.toughness.isNotEmpty()) builder.setToughness(Int32Value.newBuilder().setValue(card.toughness.toIntOrNull() ?: 0))
        var abilitySeqId = 50
        val abilities = card.abilityIds.ifEmpty {
            basicLandAbility(card.subtypes)?.let { listOf(it to 0) } ?: emptyList()
        }
        abilities.forEach { (abilityGrpId, _) ->
            builder.addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(abilityGrpId))
        }
        return builder
    }

    /** Build a [GameObjectInfo] from DB data, preserving template structure fields. */
    fun buildObjectInfo(grpId: Int, template: GameObjectInfo): GameObjectInfo {
        val card = lookup(grpId) ?: return template.toBuilder().setGrpId(grpId).setOverlayGrpId(grpId).build()

        val builder = template.toBuilder()
            .setGrpId(grpId)
            .setOverlayGrpId(grpId)
            .setName(card.titleId)

        // Card types
        builder.clearCardTypes()
        card.types.forEach { builder.addCardTypes(CardType.forNumber(it) ?: return@forEach) }

        // Subtypes
        builder.clearSubtypes()
        card.subtypes.forEach { builder.addSubtypes(SubType.forNumber(it) ?: return@forEach) }

        // Supertypes
        builder.clearSuperTypes()
        card.supertypes.forEach { builder.addSuperTypes(SuperType.forNumber(it) ?: return@forEach) }

        // Colors
        builder.clearColor()
        card.colors.forEach { builder.addColor(CardColor.forNumber(it) ?: return@forEach) }

        // Power/toughness (only for creatures)
        if (card.power.isNotEmpty()) {
            builder.setPower(Int32Value.newBuilder().setValue(card.power.toIntOrNull() ?: 0))
        } else {
            builder.clearPower()
        }
        if (card.toughness.isNotEmpty()) {
            builder.setToughness(Int32Value.newBuilder().setValue(card.toughness.toIntOrNull() ?: 0))
        } else {
            builder.clearToughness()
        }

        // Abilities — abilityGrpId is the lookup key, id is sequential per object
        builder.clearUniqueAbilities()
        var abilitySeqId = template.uniqueAbilitiesList.firstOrNull()?.id ?: 50
        val abilities = card.abilityIds.ifEmpty {
            basicLandAbility(card.subtypes)?.let { listOf(it to 0) } ?: emptyList()
        }
        abilities.forEach { (abilityGrpId, _) ->
            builder.addUniqueAbilities(
                UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(abilityGrpId),
            )
        }

        return builder.build()
    }

    private fun query(dbPath: String, grpId: Int): CardData? {
        return try {
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                conn.prepareStatement(
                    "SELECT GrpId, TitleId, Power, Toughness, Colors, Types, Subtypes, Supertypes, AbilityIds, OldSchoolManaText, AbilityIdToLinkedTokenGrpId FROM Cards WHERE GrpId = ?",
                ).use { stmt ->
                    stmt.setInt(1, grpId)
                    val rs = stmt.executeQuery()
                    if (!rs.next()) return null
                    CardData(
                        grpId = rs.getInt("GrpId"),
                        titleId = rs.getInt("TitleId"),
                        power = rs.getString("Power") ?: "",
                        toughness = rs.getString("Toughness") ?: "",
                        colors = parseIntList(rs.getString("Colors")),
                        types = parseIntList(rs.getString("Types")),
                        subtypes = parseIntList(rs.getString("Subtypes")),
                        supertypes = parseIntList(rs.getString("Supertypes")),
                        abilityIds = parseAbilityIds(rs.getString("AbilityIds")),
                        manaCost = parseManaCost(rs.getString("OldSchoolManaText")),
                        tokenGrpIds = parseTokenGrpIds(rs.getString("AbilityIdToLinkedTokenGrpId")),
                    )
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to query client card DB for grpId={}: {}", grpId, e.message)
            null
        }
    }

    /**
     * Look up the token grpId produced by a source card.
     * If the source card produces exactly one token type, returns it directly.
     * If multiple, matches by [tokenName] against the token's localized name.
     * Returns null if source card has no token mappings or no match found.
     */
    fun tokenGrpIdForCard(sourceGrpId: Int, tokenName: String? = null): Int? {
        val data = lookup(sourceGrpId) ?: return null
        val tokens = data.tokenGrpIds
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) return tokens.values.first()
        if (tokenName == null) return null
        // Multiple tokens — match by name
        for ((_, tokenGrpId) in tokens) {
            val name = getCardName(tokenGrpId) ?: run {
                // Try loading from DB
                lookup(tokenGrpId)
                getCardName(tokenGrpId)
            }
            if (name == tokenName) return tokenGrpId
        }
        return null
    }

    /** Parse "5" or "27,23" → list of ints. */
    private fun parseIntList(s: String?): List<Int> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    /**
     * Basic land mana ability grpIds — implicit in the client, not stored in DB.
     * SubType enum values: Plains=54, Island=43, Swamp=69, Mountain=49, Forest=29.
     */
    private val BASIC_LAND_ABILITIES = mapOf(
        54 to 1001, // Plains → {T}: Add {W}
        43 to 1002, // Island → {T}: Add {U}
        69 to 1003, // Swamp → {T}: Add {B}
        49 to 1004, // Mountain → {T}: Add {R}
        29 to 1005, // Forest → {T}: Add {G}
    )

    /** Returns the implicit mana ability grpId for a basic land, or null. */
    private fun basicLandAbility(subtypes: List<Int>): Int? =
        subtypes.firstNotNullOfOrNull { BASIC_LAND_ABILITIES[it] }

    /** Parse "1005:227393" or "1005:227393 2010:300000" → list of (abilityGrpId, textId). */
    private fun parseAbilityIds(s: String?): List<Pair<Int, Int>> {
        if (s.isNullOrBlank()) return emptyList()
        return s.trim().split(" ", ",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val base = parts[0].toIntOrNull() ?: return@mapNotNull null
                val id = parts[1].toIntOrNull() ?: return@mapNotNull null
                base to id
            } else {
                null
            }
        }
    }

    /** Parse "99866:94161,175756:94156" → mapOf(99866 to 94161, 175756 to 94156). */
    internal fun parseTokenGrpIds(s: String?): Map<Int, Int> {
        if (s.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<Int, Int>()
        for (entry in s.split(",")) {
            val parts = entry.trim().split(":")
            if (parts.size == 2) {
                val abilityGrpId = parts[0].toIntOrNull() ?: continue
                val tokenGrpId = parts[1].toIntOrNull() ?: continue
                result[abilityGrpId] = tokenGrpId
            }
        }
        return result
    }

    private fun queryByName(dbPath: String, cardName: String): Int? = try {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.prepareStatement(
                """
                    SELECT c.GrpId FROM Cards c
                    JOIN Localizations_enUS l ON c.TitleId = l.LocId
                    WHERE l.Formatted = 1 AND l.Loc = ?
                      AND c.IsToken = 0 AND c.IsPrimaryCard = 1
                    ORDER BY c.IsDigitalOnly ASC, c.IsRebalanced ASC, c.GrpId DESC
                    LIMIT 1
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, cardName)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getInt("GrpId") else null
            }
        }
    } catch (e: Exception) {
        log.warn("Failed to query client card DB for name='{}': {}", cardName, e.message)
        null
    }

    /**
     * Parse the client's OldSchoolManaText format into (ManaColor, count) pairs.
     * Format: "oG" = {G}, "o3oGoG" = {3}{G}{G}, "oXoRoR" = {X}{R}{R}.
     * Each "o" prefix starts a mana symbol; digits = generic count, letters = color.
     */
    private fun parseManaCost(s: String?): List<Pair<ManaColor, Int>> {
        if (s.isNullOrBlank()) return emptyList()
        val counts = mutableMapOf<ManaColor, Int>()
        for (part in s.split("o").filter { it.isNotEmpty() }) {
            when (part.uppercase()) {
                "W" -> counts.merge(ManaColor.White_afc9, 1, Int::plus)
                "U" -> counts.merge(ManaColor.Blue_afc9, 1, Int::plus)
                "B" -> counts.merge(ManaColor.Black_afc9, 1, Int::plus)
                "R" -> counts.merge(ManaColor.Red_afc9, 1, Int::plus)
                "G" -> counts.merge(ManaColor.Green_afc9, 1, Int::plus)
                "X" -> counts.merge(ManaColor.X, 1, Int::plus)
                "C" -> counts.merge(ManaColor.Colorless_afc9, 1, Int::plus)
                "S" -> counts.merge(ManaColor.Snow_afc9, 1, Int::plus)
                else -> {
                    val n = part.toIntOrNull()
                    if (n != null && n > 0) counts.merge(ManaColor.Generic, n, Int::plus)
                }
            }
        }
        return counts.toList()
    }
}
