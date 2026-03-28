package leyline.frontdoor.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.Format
import leyline.frontdoor.domain.PlayerId
import org.slf4j.LoggerFactory

/**
 * Translates [Deck] domain objects to Arena wire JSON shapes.
 *
 * Two summary formats exist:
 * - **V2** (StartHook, older CmdTypes): Attributes = `[{name,value}]` array
 * - **V3** (CmdType 410): Attributes = flat `{key: value}` dict
 */
object DeckWireBuilder {

    private val log = LoggerFactory.getLogger(DeckWireBuilder::class.java)

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** V2 summary: Attributes as [{name,value}] array. Used in StartHook. */
    fun toV2Summary(deck: Deck): JsonObject = buildJsonObject {
        putCommonFields(deck)
        put(
            "Attributes",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("name", "Version")
                        put("value", "1")
                    },
                )
                add(
                    buildJsonObject {
                        put("name", "TileID")
                        put("value", deck.tileId.toString())
                    },
                )
                add(
                    buildJsonObject {
                        put("name", "LastPlayed")
                        put("value", "\"0001-01-01T00:00:00\"")
                    },
                )
                add(
                    buildJsonObject {
                        put("name", "LastUpdated")
                        put("value", "\"0001-01-01T00:00:00\"")
                    },
                )
                add(
                    buildJsonObject {
                        put("name", "IsFavorite")
                        put("value", deck.isFavorite.toString())
                    },
                )
                add(
                    buildJsonObject {
                        put("name", "Format")
                        put("value", deck.format.name)
                    },
                )
            },
        )
        putTrailingFields(deck)
    }

    /** V3 summary: Attributes as flat dict. Used in CmdType 410. */
    fun toV3Summary(deck: Deck): JsonObject = buildJsonObject {
        putCommonFields(deck)
        put(
            "Attributes",
            buildJsonObject {
                put("Version", "1")
                put("Format", deck.format.name)
                put("TileID", deck.tileId.toString())
            },
        )
    }

    /** Deck entry for StartHook's Decks map (card lists only — matches golden shape). */
    fun toStartHookEntry(deck: Deck): JsonObject = buildJsonObject {
        put("MainDeck", cardsToJsonArray(deck.mainDeck))
        put("ReducedSideboard", buildJsonArray {})
        put("Sideboard", cardsToJsonArray(deck.sideboard))
        put("CommandZone", cardsToJsonArray(deck.commandZone))
        put("Companions", cardsToJsonArray(deck.companions))
        put("CardSkins", buildJsonArray {})
    }

    /** Serialize card list to Arena JSON shape `[{cardId, quantity}]`. */
    fun cardsToJsonArray(cards: List<DeckCard>) = buildJsonArray {
        for (c in cards) {
            add(
                buildJsonObject {
                    put("cardId", c.grpId)
                    put("quantity", c.quantity)
                },
            )
        }
    }

    /**
     * Parse CmdType 406 inbound JSON into a [Deck].
     *
     * Wire format: `{"Summary": {DeckId, Name, DeckTileId, ...}, "Deck": {MainDeck, ...}, "ActionType": ...}`
     */
    fun parseDeckUpdate(json: String, playerId: PlayerId): Deck? = try {
        val root = lenientJson.parseToJsonElement(json).jsonObject
        val summary = root["Summary"]?.jsonObject ?: return null
        val deckId = summary["DeckId"]?.jsonPrimitive?.content ?: return null
        val name = summary["Name"]?.jsonPrimitive?.content ?: "Unnamed"
        val tileId = summary["DeckTileId"]?.jsonPrimitive?.int ?: 0
        val deckObj = root["Deck"]?.jsonObject ?: return null

        val attrs = parseAttributes(summary)
        val actionType = root["ActionType"]?.jsonPrimitive?.content
        if (actionType != null) {
            log.info("Deck upsert action={} deck={}", actionType, name)
        }

        Deck(
            id = DeckId(deckId),
            playerId = playerId,
            name = name,
            format = attrs["Format"]?.let { Format.fromString(it) } ?: Format.Standard,
            tileId = tileId,
            mainDeck = parseCardList(deckObj["MainDeck"]),
            sideboard = parseCardList(deckObj["Sideboard"]),
            commandZone = parseCardList(deckObj["CommandZone"]),
            companions = parseCardList(deckObj["Companions"]),
            isFavorite = attrs["IsFavorite"]?.equals("true", ignoreCase = true) == true,
        )
    } catch (_: Exception) {
        null
    }

    /** Extract V2-style `[{name,value}]` attributes into a flat map. */
    fun parseAttributes(summary: JsonObject): Map<String, String> {
        val attrs = summary["Attributes"]?.jsonArray ?: return emptyMap()
        return attrs.mapNotNull { el ->
            val obj = el.jsonObject
            val k = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val v = obj["value"]?.jsonPrimitive?.content ?: return@mapNotNull null
            k to v
        }.toMap()
    }

    // -- private helpers --

    private fun kotlinx.serialization.json.JsonObjectBuilder.putCommonFields(deck: Deck) {
        put("DeckId", deck.id.value)
        put("Name", deck.name)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTrailingFields(deck: Deck) {
        put("DeckTileId", deck.tileId)
        put("DeckArtId", 0)
        put("FormatLegalities", formatLegalities(deck))
        put("PreferredCosmetics", preferredCosmetics())
        put("DeckValidationSummaries", buildJsonArray {})
        put("UnownedCards", buildJsonObject {})
    }

    /**
     * Format legality keys the client expects. Subset of real server's ~143 formats.
     *
     * Real server returns ~143 keys. We emit the ones the client checks for deck
     * filtering in queue selection. Missing keys default to false on the client.
     *
     * For Brawl decks: Brawl + DirectGameBrawl variants = true, everything else false.
     * For constructed decks (≥60 cards): constructed + DirectGame variants = true.
     */
    private val CONSTRUCTED_FORMATS = listOf(
        "Standard", "Historic", "Explorer", "Timeless", "Alchemy",
        "TraditionalStandard", "TraditionalHistoric", "TraditionalExplorer", "TraditionalTimeless",
        "DirectGame", "DirectGameAlchemy",
    )
    private val LIMITED_FORMATS = listOf(
        "Draft",
        "Sealed",
        "Draft_Rebalanced",
        "Sealed_Rebalanced",
        "DirectGameLimited",
        "DirectGameLimitedRebalanced",
    )

    private fun formatLegalities(deck: Deck): JsonObject {
        val isBrawl = deck.format == Format.Brawl
        val mainDeckCards = deck.mainDeck.sumOf { it.quantity }
        val constructedLegal = !isBrawl && mainDeckCards >= 60
        // Standard Brawl (59 main) vs Historic Brawl (99 main)
        val isStandardBrawl = isBrawl && mainDeckCards < 80
        val isHistoricBrawl = isBrawl && mainDeckCards >= 80
        return buildJsonObject {
            for (fmt in CONSTRUCTED_FORMATS) put(fmt, constructedLegal)
            put("Brawl", isStandardBrawl)
            put("HistoricBrawl", isHistoricBrawl)
            put("DirectGameBrawl", isBrawl)
            put("DirectGameBrawlRebalanced", isBrawl)
            put("AllZeroes", true)
            put("StandardBrawl", isStandardBrawl)
            for (fmt in LIMITED_FORMATS) put(fmt, true)
        }
    }

    private fun preferredCosmetics(): JsonObject = buildJsonObject {
        put("Avatar", "")
        put("Sleeve", "")
        put("Pet", "")
        put("Title", "")
        put("Emotes", buildJsonArray {})
    }

    /** Parse Arena `[{cardId, quantity}]` array to [DeckCard] list. Skips malformed entries with a warning. */
    fun parseCardList(element: JsonElement?): List<DeckCard> {
        if (element == null) return emptyList()
        return try {
            element.jsonArray.mapNotNull { entry ->
                val obj = entry.jsonObject
                val grpId = obj["cardId"]?.jsonPrimitive?.int
                val qty = obj["quantity"]?.jsonPrimitive?.int
                if (grpId == null || qty == null) {
                    log.warn("DeckWireBuilder: skipping malformed card entry: {}", entry)
                    return@mapNotNull null
                }
                DeckCard(grpId, qty)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
