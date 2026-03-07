package leyline.frontdoor.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.frontdoor.domain.DeckCard
import org.slf4j.LoggerFactory

/**
 * Typed request parsers for Front Door CmdTypes.
 *
 * Field names match the client's wire casing exactly (from proxy captures).
 * Uses lenient JSON parsing (`ignoreUnknownKeys`) so new fields the client
 * adds don't break existing handlers.
 */
object FdRequests {

    private val log = LoggerFactory.getLogger(FdRequests::class.java)

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** CmdType 612 — client uses camelCase. */
    data class AiBotMatch(val deckId: String, val botDeckId: String?, val botMatchType: Int?)

    /** CmdType 600 */
    data class EventJoin(val eventName: String, val entryCurrencyType: String?)

    /** CmdType 601, 606 */
    data class EventName(val eventName: String)

    /** CmdType 603 */
    data class EnterPairing(val eventName: String, val eventCode: String?)

    /** CmdType 608 */
    data class MatchResult(val eventName: String, val matchId: String?)

    /** CmdType 622 — eventName + deckId extracted from Summary, deck contents from Deck. */
    data class SetDeck(
        val eventName: String,
        val deckId: String?,
        val deckName: String? = null,
        val tileId: Int? = null,
        val mainDeck: List<DeckCard> = emptyList(),
        val sideboard: List<DeckCard> = emptyList(),
    )

    /** CmdType 403 */
    data class DeleteDeck(val deckId: String)

    // --- Parsers ---

    fun parseAiBotMatch(json: String?): AiBotMatch? = parse(json) { obj ->
        AiBotMatch(
            deckId = obj["deckId"]?.jsonPrimitive?.content ?: return@parse null,
            botDeckId = obj["botDeckId"]?.jsonPrimitive?.content,
            botMatchType = obj["botMatchType"]?.jsonPrimitive?.int,
        )
    }

    fun parseEventJoin(json: String?): EventJoin? = parse(json) { obj ->
        EventJoin(
            eventName = obj["EventName"]?.jsonPrimitive?.content ?: return@parse null,
            entryCurrencyType = obj["EntryCurrencyType"]?.jsonPrimitive?.content,
        )
    }

    fun parseEventName(json: String?): EventName? = parse(json) { obj ->
        val name = obj["EventName"]?.jsonPrimitive?.content ?: return@parse null
        EventName(name)
    }

    fun parseEnterPairing(json: String?): EnterPairing? = parse(json) { obj ->
        EnterPairing(
            eventName = obj["EventName"]?.jsonPrimitive?.content ?: return@parse null,
            eventCode = obj["EventCode"]?.jsonPrimitive?.content,
        )
    }

    fun parseMatchResult(json: String?): MatchResult? = parse(json) { obj ->
        MatchResult(
            eventName = obj["EventName"]?.jsonPrimitive?.content ?: return@parse null,
            matchId = obj["MatchId"]?.jsonPrimitive?.content,
        )
    }

    fun parseSetDeck(json: String?): SetDeck? = parse(json) { obj ->
        val summary = obj["Summary"]?.jsonObject
        val deck = obj["Deck"]?.jsonObject
        SetDeck(
            eventName = obj["EventName"]?.jsonPrimitive?.content ?: return@parse null,
            deckId = summary?.get("DeckId")?.jsonPrimitive?.content,
            deckName = summary?.get("Name")?.jsonPrimitive?.content,
            tileId = summary?.get("DeckTileId")?.jsonPrimitive?.int,
            mainDeck = parseDeckCards(deck?.get("MainDeck")),
            sideboard = parseDeckCards(deck?.get("Sideboard")),
        )
    }

    fun parseDeleteDeck(json: String?): DeleteDeck? = parse(json) { obj ->
        val id = obj["DeckId"]?.jsonPrimitive?.content ?: return@parse null
        DeleteDeck(id)
    }

    private fun parseDeckCards(element: JsonElement?): List<DeckCard> {
        if (element == null) return emptyList()
        return try {
            element.jsonArray.map { card ->
                val obj = card.jsonObject
                DeckCard(
                    grpId = obj["cardId"]?.jsonPrimitive?.int ?: 0,
                    quantity = obj["quantity"]?.jsonPrimitive?.int ?: 1,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private inline fun <T> parse(
        json: String?,
        block: (kotlinx.serialization.json.JsonObject) -> T?,
    ): T? {
        if (json == null) return null
        return try {
            block(lenientJson.parseToJsonElement(json).jsonObject)
        } catch (e: Exception) {
            log.warn("FdRequests: parse failed: {}", e.message)
            null
        }
    }
}
