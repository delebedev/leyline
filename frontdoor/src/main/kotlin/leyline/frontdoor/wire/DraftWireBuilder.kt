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
        }.toString()
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
