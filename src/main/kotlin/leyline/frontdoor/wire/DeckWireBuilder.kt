package leyline.frontdoor.wire

import kotlinx.serialization.json.Json
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

/**
 * Translates [Deck] domain objects to Arena wire JSON shapes.
 *
 * Two summary formats exist:
 * - **V2** (StartHook, older CmdTypes): Attributes = `[{name,value}]` array
 * - **V3** (CmdType 410): Attributes = flat `{key: value}` dict
 */
object DeckWireBuilder {

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
                        put("name", "Format")
                        put("value", deck.format.name)
                    },
                )
                add(
                    buildJsonObject {
                        put("name", "TileID")
                        put("value", deck.tileId.toString())
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
        putTrailingFields(deck)
    }

    /** Deck entry for StartHook's Decks map (full cards JSON). */
    fun toStartHookEntry(deck: Deck): JsonObject = buildJsonObject {
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
                        put("name", "Format")
                        put("value", deck.format.name)
                    },
                )
                add(
                    buildJsonObject {
                        put("name", "TileID")
                        put("value", deck.tileId.toString())
                    },
                )
            },
        )
        putTrailingFields(deck)
        put("MainDeck", cardsToJsonArray(deck.mainDeck))
        put("Sideboard", cardsToJsonArray(deck.sideboard))
        put("CommandZone", cardsToJsonArray(deck.commandZone))
        put("Companions", cardsToJsonArray(deck.companions))
        put("CardSkins", buildJsonArray {})
    }

    /** Parse CmdType 406 inbound JSON into a [Deck]. */
    fun parseDeckUpdate(json: String, playerId: PlayerId): Deck? = try {
        val obj = lenientJson.parseToJsonElement(json).jsonObject
        val deckId = obj["DeckId"]?.jsonPrimitive?.content ?: return null
        val name = obj["Name"]?.jsonPrimitive?.content ?: "Unnamed"
        val tileId = obj["DeckTileId"]?.jsonPrimitive?.int ?: 0
        val deckObj = obj["Deck"]?.jsonObject ?: return null
        Deck(
            id = DeckId(deckId),
            playerId = playerId,
            name = name,
            format = Format.Standard,
            tileId = tileId,
            mainDeck = parseCardList(deckObj["MainDeck"]),
            sideboard = parseCardList(deckObj["Sideboard"]),
            commandZone = parseCardList(deckObj["CommandZone"]),
            companions = parseCardList(deckObj["Companions"]),
        )
    } catch (_: Exception) {
        null
    }

    // -- private helpers --

    private fun kotlinx.serialization.json.JsonObjectBuilder.putCommonFields(deck: Deck) {
        put("DeckId", deck.id.value)
        put("Name", deck.name)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTrailingFields(deck: Deck) {
        put("DeckTileId", deck.tileId)
        put("DeckArtId", 0)
        put("FormatLegalities", formatLegalities(deck.totalCards))
        put("PreferredCosmetics", preferredCosmetics())
        put("DeckValidationSummaries", buildJsonArray {})
        put("UnownedCards", buildJsonObject {})
    }

    private fun formatLegalities(totalCards: Int): JsonObject {
        val legal = totalCards >= 60
        return buildJsonObject {
            put("Standard", legal)
            put("Historic", legal)
            put("Explorer", legal)
            put("Timeless", legal)
            put("Alchemy", legal)
            put("Brawl", false)
        }
    }

    private fun preferredCosmetics(): JsonObject = buildJsonObject {
        put("Avatar", "")
        put("Sleeve", "")
        put("Pet", "")
        put("Title", "")
        put("Emotes", buildJsonArray {})
    }

    private fun cardsToJsonArray(cards: List<DeckCard>) = buildJsonArray {
        for (c in cards) {
            add(
                buildJsonObject {
                    put("cardId", c.grpId)
                    put("quantity", c.quantity)
                },
            )
        }
    }

    private fun parseCardList(element: kotlinx.serialization.json.JsonElement?): List<DeckCard> {
        if (element == null) return emptyList()
        return try {
            element.jsonArray.map { entry ->
                val obj = entry.jsonObject
                DeckCard(
                    grpId = obj["cardId"]?.jsonPrimitive?.int ?: 0,
                    quantity = obj["quantity"]?.jsonPrimitive?.int ?: 0,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
