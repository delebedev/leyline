package leyline.frontdoor.wire

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import leyline.frontdoor.domain.DraftSession
import leyline.frontdoor.domain.DraftStatus

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

    private fun buildInventoryInfo(session: DraftSession) = buildJsonObject {
        put("SeqId", 1)
        put(
            "Changes",
            buildJsonArray {
                if (session.status == DraftStatus.Completed) {
                    add(
                        buildJsonObject {
                            put("Source", "EventGrantCardPool")
                            put("SourceId", session.eventName)
                            put(
                                "GrantedCards",
                                buildJsonArray {
                                    session.pickedCards.forEach { grpId ->
                                        add(
                                            buildJsonObject {
                                                put("GrpId", grpId)
                                                put("CardAdded", true)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
        put("Gems", 0)
        put("Gold", 0)
        put("TotalVaultProgress", 0)
        put("WildCardCommons", 0)
        put("WildCardUnCommons", 0)
        put("WildCardRares", 0)
        put("WildCardMythics", 0)
        put("CustomTokens", buildJsonObject {})
        put("Boosters", buildJsonArray {})
    }

    private fun buildPayloadJson(session: DraftSession): String = buildJsonObject {
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
}
