package leyline.native.frontdoor.wire

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import leyline.domain.DraftSession
import leyline.domain.DraftStatus

/**
 * Serializes BotDraft responses as Course-wrapped double-encoded JSON.
 *
 * Wire format: `{"CurrentModule":"BotDraft","Payload":"{...}"}`
 * On completion, CurrentModule switches to "DeckSelect".
 */
object DraftWireBuilder {
    fun buildDraftResponse(session: DraftSession): String {
        val payload = buildPayloadJson(session)
        val module = if (session.status == DraftStatus.Completed) "DeckSelect" else "BotDraft"
        return buildJsonObject {
            put("CurrentModule", module)
            put("Payload", payload)
            put("DTO_InventoryInfo", buildInventoryInfo(session))
        }.toString()
    }

    private fun buildInventoryInfo(session: DraftSession) =
        buildJsonObject {
            put("SeqId", 1)
            putJsonArray("Changes") {
                if (session.status == DraftStatus.Completed) add(buildCardPoolGrantChange(session))
            }
            put("Gems", 0)
            put("Gold", 0)
            put("TotalVaultProgress", 0)
            put("wcTrackPosition", 0)
            put("WildCardCommons", 0)
            put("WildCardUnCommons", 0)
            put("WildCardRares", 0)
            put("WildCardMythics", 0)
            putJsonObject("CustomTokens") {}
            putJsonArray("Boosters") {}
            putJsonObject("Vouchers") {}
            putJsonArray("PrizeWallsUnlocked") {}
            putJsonObject("Cosmetics") {
                putJsonArray("ArtStyles") {}
                putJsonArray("Avatars") {}
                putJsonArray("Pets") {}
                putJsonArray("Sleeves") {}
                putJsonArray("Emotes") {}
                putJsonArray("Titles") {}
            }
        }

    private fun buildCardPoolGrantChange(session: DraftSession) =
        buildJsonObject {
            val setCode = extractSetCode(session.eventName)
            put("Source", "EventGrantCardPool")
            put("SourceId", session.eventName)
            putJsonObject("InventoryCustomTokens") {}
            putJsonArray("ArtStyles") {}
            putJsonArray("Avatars") {}
            putJsonArray("Sleeves") {}
            putJsonArray("Pets") {}
            putJsonArray("Emotes") {}
            putJsonArray("Titles") {}
            putJsonArray("Decks") {}
            putJsonArray("DecksV2") {}
            putJsonArray("DecksV3") {}
            putJsonObject("DeckCards") {}
            putJsonArray("Boosters") {}
            putJsonArray("GrantedCards") {
                session.pickedCards.forEach { grpId ->
                    add(
                        buildJsonObject {
                            put("GrpId", grpId)
                            put("CardAdded", true)
                            put("SetCode", setCode)
                        },
                    )
                }
            }
            putJsonObject("Vouchers") {}
            putJsonArray("NewLetters") {}
            putJsonArray("PrizeWallsUnlocked") {}
        }

    private fun buildPayloadJson(session: DraftSession): String =
        buildJsonObject {
            put("Result", "Success")
            put("EventName", session.eventName)
            put("DraftStatus", session.status.wireName())
            put("PackNumber", session.packNumber)
            put("PickNumber", session.pickNumber)
            put("NumCardsToPick", 1)
            put(
                "DraftPack",
                buildJsonArray {
                    session.draftPack.forEach { add(JsonPrimitive(it.toString())) }
                },
            )
            put("PackStyles", buildJsonArray {})
            put(
                "PickedCards",
                buildJsonArray {
                    session.pickedCards.forEach { add(JsonPrimitive(it.toString())) }
                },
            )
            put("PickedStyles", buildJsonArray {})
        }.toString()

    private fun extractSetCode(eventName: String): String {
        val parts = eventName.split("_")
        return if (parts.size >= 2 && parts[0].equals("QuickDraft", ignoreCase = true)) parts[1] else "FDN"
    }
}
