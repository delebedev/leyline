package leyline.frontdoor.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Wire-format helpers for player-related protocol shapes.
 */
object PlayerWireBuilder {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parse preferences JSON, guarding against the double-wrap quirk.
     *
     * Client sometimes sends `{"Preferences":{"Preferences":{...}}}` —
     * this unwraps one layer if detected, returning `{"Preferences":{...}}`.
     */
    fun parsePreferences(json: String): String = try {
        val obj = lenientJson.parseToJsonElement(json).jsonObject
        val inner = obj["Preferences"]?.jsonObject
        if (inner != null && inner.containsKey("Preferences")) {
            lenientJson.encodeToString(JsonObject.serializer(), inner)
        } else {
            json
        }
    } catch (_: Exception) {
        json
    }
}
