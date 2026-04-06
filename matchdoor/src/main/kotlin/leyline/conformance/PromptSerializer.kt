package leyline.conformance

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Lossless proto→JSON for engine prompt messages.
 *
 * Uses [JsonFormat.printer] with [preservingProtoFieldNames] so output
 * matches the protocol decoder's format — enabling field-level
 * conformance diffing.
 */
object PromptSerializer {

    private val printer = JsonFormat.printer()
        .omittingInsignificantWhitespace()
        .preservingProtoFieldNames()

    fun serialize(msg: DeclareAttackersReq): JsonElement =
        Json.parseToJsonElement(printer.print(msg))

    fun serialize(msg: DeclareAttackersResp): JsonElement =
        Json.parseToJsonElement(printer.print(msg))

    fun serialize(msg: DeclareBlockersReq): JsonElement =
        Json.parseToJsonElement(printer.print(msg))

    fun serialize(msg: DeclareBlockersResp): JsonElement =
        Json.parseToJsonElement(printer.print(msg))

    fun serialize(msg: GroupReq): JsonElement =
        Json.parseToJsonElement(printer.print(msg))

    fun serialize(msg: GroupResp): JsonElement =
        Json.parseToJsonElement(printer.print(msg))
}
