package leyline.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * Front Door protobuf envelope codec.
 *
 * The FD uses a different protobuf schema from the Match Door (not in messages.proto).
 * Field numbers from IL2CPP dump: `mtga-internals/docs/fd-envelope-proto.md`.
 *
 * Three envelope types:
 * - **Cmd** (C→S command / S→C push): type=1, raw_trans_id=2, {proto=3, json=4}, compressed=5
 * - **Request** (C→S newer path): type=1, raw_trans_id=2, key=3, {proto=4, json=5}, session_info=6, compressed=7
 * - **Response** (S→C reply): raw_trans_id=1, {proto=2, json=3}, error=4, compressed=5
 */
object FdEnvelope {

    // --- Cmd field numbers ---
    private const val CMD_TYPE = 1 // varint (CmdType enum)
    private const val CMD_TRANS_ID = 2 // string
    private const val CMD_JSON_PAYLOAD = 4 // bytes (JSON)

    // --- Request field numbers ---
    private const val REQ_TYPE = 1 // varint (RequestType)
    private const val REQ_TRANS_ID = 2 // string
    private const val REQ_KEY = 3 // string
    private const val REQ_JSON_PAYLOAD = 5 // bytes (JSON)

    // --- Response field numbers ---
    private const val RESP_TRANS_ID = 1 // string
    private const val RESP_JSON_PAYLOAD = 3 // bytes (JSON)

    // Protobuf wire types
    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2

    /**
     * Decoded FD message — works for all three envelope types.
     */
    data class FdMessage(
        /** CmdType value. Present in Cmd/Request envelopes, null for Response. */
        val cmdType: Int?,
        /** Transaction GUID (raw_trans_id). */
        val transactionId: String?,
        /** Decoded JSON payload string, if present. */
        val jsonPayload: String?,
        /** Routing key (Request envelope only). */
        val key: String? = null,
        /** Which envelope type this was decoded from. */
        val envelopeType: EnvelopeType = EnvelopeType.UNKNOWN,
    )

    enum class EnvelopeType { CMD, REQUEST, RESPONSE, UNKNOWN }

    /**
     * Decode raw protobuf bytes into an [FdMessage].
     *
     * Heuristic to distinguish envelope types:
     * - If field 1 is a varint → Cmd or Request (both have type=1 as varint)
     * - If field 1 is length-delimited → Response (raw_trans_id=1 as string)
     *
     * We then look at which json_payload field is present:
     * - Field 4 (bytes) → Cmd
     * - Field 5 (bytes) → Request
     * - Field 3 (bytes) after string field 1 → Response
     */
    fun decode(bytes: ByteArray): FdMessage {
        val fields = parseProtoFields(bytes)

        // Determine envelope type by field 1's wire type
        val field1 = fields.firstOrNull { it.fieldNumber == 1 }

        // Check for compressed flag (field 5 in Cmd/Response, field 7 in Request)
        val isCompressed = fields.any {
            it.wireType == WIRE_VARINT && it.fieldNumber in listOf(5, 7) && it.asVarint() != 0
        }

        return when {
            // Response: field 1 is string (raw_trans_id)
            field1 != null &&
                field1.wireType == WIRE_LENGTH_DELIMITED &&
                isLikelyUuid(field1.asString()) -> {
                val transId = field1.asString()
                val rawPayload = fields.firstOrNull { it.fieldNumber == RESP_JSON_PAYLOAD }
                val json = rawPayload?.let { decompress(it.data, isCompressed) }
                FdMessage(
                    cmdType = null,
                    transactionId = transId,
                    jsonPayload = json,
                    envelopeType = EnvelopeType.RESPONSE,
                )
            }
            // Cmd or Request: field 1 is varint (type)
            field1 != null && field1.wireType == WIRE_VARINT -> {
                decodeCmd(fields, field1.asVarint(), isCompressed)
            }
            // CmdType=0 (Authenticate): protobuf omits varint 0 for default values,
            // so field 1 is absent. Detect by: field 2 is UUID, field 4 is payload.
            field1 == null || (field1.wireType == WIRE_LENGTH_DELIMITED && !isLikelyUuid(field1.asString())) -> {
                val field2 = fields.firstOrNull { it.fieldNumber == 2 }
                val field4 = fields.firstOrNull { it.fieldNumber == 4 }
                if (field2 != null && isLikelyUuid(field2.asString())) {
                    // Cmd with CmdType=0 (omitted default)
                    decodeCmd(fields, 0, isCompressed)
                } else {
                    fallback(fields)
                }
            }
            else -> fallback(fields)
        }
    }

    private fun decodeCmd(fields: List<ProtoField>, cmdType: Int, isCompressed: Boolean): FdMessage {
        val transId = fields.firstOrNull { it.fieldNumber == 2 }?.asString()
        val rawField5 = fields.firstOrNull { it.fieldNumber == 5 }
        val rawField4 = fields.firstOrNull { it.fieldNumber == 4 }
        val key = fields.firstOrNull { it.fieldNumber == 3 }
            ?.takeIf { rawField5 != null }
            ?.asString()

        return if (rawField5 != null) {
            FdMessage(
                cmdType = cmdType,
                transactionId = transId,
                jsonPayload = decompress(rawField5.data, isCompressed),
                key = key,
                envelopeType = EnvelopeType.REQUEST,
            )
        } else {
            FdMessage(
                cmdType = cmdType,
                transactionId = transId,
                jsonPayload = rawField4?.let { decompress(it.data, isCompressed) },
                envelopeType = EnvelopeType.CMD,
            )
        }
    }

    private fun fallback(fields: List<ProtoField>): FdMessage {
        val anyJson = fields.filter { it.wireType == WIRE_LENGTH_DELIMITED }
            .mapNotNull { it.asString() }
            .firstOrNull { it.startsWith("{") }
        val anyUuid = fields.filter { it.wireType == WIRE_LENGTH_DELIMITED }
            .mapNotNull { it.asString() }
            .firstOrNull { UUID_PATTERN.matches(it) }
        return FdMessage(null, anyUuid, anyJson)
    }

    /**
     * Decompress gzip payload if flagged, otherwise decode as UTF-8.
     *
     * Compressed payloads have a 4-byte uint32 LE prefix (uncompressed size)
     * followed by standard gzip data (magic bytes 1f 8b).
     */
    private fun decompress(data: ByteArray, compressed: Boolean): String? {
        if (!compressed) {
            return try {
                String(data, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
        // Find gzip magic (1f 8b) — typically at offset 4 after the size prefix
        val gzipOffset = findGzipMagic(data)
        val stream = if (gzipOffset >= 0) data.copyOfRange(gzipOffset, data.size) else data
        return try {
            GZIPInputStream(ByteArrayInputStream(stream)).bufferedReader(Charsets.UTF_8).readText()
        } catch (_: Exception) {
            // Not actually gzip — try raw UTF-8
            try {
                String(data, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Find gzip magic bytes (1f 8b) within first 8 bytes. */
    private fun findGzipMagic(data: ByteArray): Int {
        if (data.size < 2) return -1
        val limit = minOf(8, data.size - 2)
        for (i in 0..limit) {
            if (data[i] == 0x1f.toByte() && data[i + 1] == 0x8b.toByte()) return i
        }
        return -1
    }

    private fun isLikelyUuid(s: String?): Boolean = s != null && UUID_PATTERN.matches(s)

    // --- Response field 2 (protobuf_payload) ---
    private const val RESP_PROTO_PAYLOAD = 2 // bytes (protobuf Any)
    private const val ANY_TYPE_URL = 1 // string field in google.protobuf.Any
    private const val ANY_VALUE = 2 // bytes field in google.protobuf.Any
    private const val TYPE_PREFIX = "type.googleapis.com/"

    /**
     * Encode an empty Response envelope (S→C ack, no payload).
     * Client's UnpackPayload may NRE on truly empty responses —
     * prefer [encodeProtoResponse] with the correct type URL.
     */
    fun encodeEmptyResponse(transactionId: String): ByteArray {
        val buf = ByteArrayOutputStream()
        writeString(buf, RESP_TRANS_ID, transactionId)
        return buf.toByteArray()
    }

    /**
     * Encode a Response with protobuf_payload (field 2) as a google.protobuf.Any.
     * The Any contains the type URL and an empty value (default proto message).
     * Used for CmdTypes where the real server sends protobuf, not JSON.
     */
    fun encodeProtoResponse(transactionId: String, typeUrl: String): ByteArray {
        // Build inner Any: field 1 = type_url, field 2 = empty bytes
        val anyBuf = ByteArrayOutputStream()
        writeString(anyBuf, ANY_TYPE_URL, typeUrl)
        // field 2 (value) omitted = empty/default protobuf message
        val anyBytes = anyBuf.toByteArray()

        val buf = ByteArrayOutputStream()
        writeString(buf, RESP_TRANS_ID, transactionId)
        writeBytes(buf, RESP_PROTO_PAYLOAD, anyBytes)
        return buf.toByteArray()
    }

    /**
     * Encode a Response with raw pre-built field 2 bytes (protobuf_payload).
     * The [protoPayload] must already be a serialized google.protobuf.Any
     * (type_url + value). Used when replaying golden capture data.
     */
    fun encodeRawProtoResponse(transactionId: String, protoPayload: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream()
        writeString(buf, RESP_TRANS_ID, transactionId)
        writeBytes(buf, RESP_PROTO_PAYLOAD, protoPayload)
        return buf.toByteArray()
    }

    /**
     * Encode a Response envelope (S→C reply to a request) with JSON in field 3.
     */
    fun encodeResponse(transactionId: String, json: String): ByteArray {
        val buf = ByteArrayOutputStream()
        writeString(buf, RESP_TRANS_ID, transactionId)
        writeBytes(buf, RESP_JSON_PAYLOAD, json.toByteArray(Charsets.UTF_8))
        return buf.toByteArray()
    }

    /**
     * Encode a Cmd envelope (S→C push notification, e.g. MatchCreated).
     */
    fun encodeCmd(cmdType: Int, transactionId: String, json: String): ByteArray {
        val buf = ByteArrayOutputStream()
        writeVarintField(buf, CMD_TYPE, cmdType)
        writeString(buf, CMD_TRANS_ID, transactionId)
        writeBytes(buf, CMD_JSON_PAYLOAD, json.toByteArray(Charsets.UTF_8))
        return buf.toByteArray()
    }

    // --- CmdType name lookup ---

    /** Human-readable name for a CmdType value, or "Unknown(N)". */
    fun cmdTypeName(cmdType: Int): String = CMD_TYPE_NAMES[cmdType] ?: "Unknown($cmdType)"

    // --- Protobuf parsing primitives ---

    private data class ProtoField(
        val fieldNumber: Int,
        val wireType: Int,
        val data: ByteArray,
    ) {
        fun asString(): String? = try {
            String(data, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }

        fun asVarint(): Int {
            var result = 0
            var shift = 0
            for (b in data) {
                result = result or ((b.toInt() and 0x7F) shl shift)
                if (b.toInt() and 0x80 == 0) break
                shift += 7
            }
            return result
        }
    }

    private fun parseProtoFields(bytes: ByteArray): List<ProtoField> {
        val fields = mutableListOf<ProtoField>()
        var offset = 0
        while (offset < bytes.size) {
            val (tag, tagLen) = readVarint(bytes, offset)
            if (tagLen == 0) break
            offset += tagLen

            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (wireType) {
                WIRE_VARINT -> {
                    val (_, varintLen) = readVarint(bytes, offset)
                    if (varintLen == 0) break
                    val varintBytes = bytes.copyOfRange(offset, offset + varintLen)
                    fields.add(ProtoField(fieldNumber, wireType, varintBytes))
                    offset += varintLen
                }
                WIRE_LENGTH_DELIMITED -> {
                    val (length, lenLen) = readVarint(bytes, offset)
                    if (lenLen == 0) break
                    offset += lenLen
                    if (offset + length > bytes.size) break
                    val data = bytes.copyOfRange(offset, offset + length)
                    fields.add(ProtoField(fieldNumber, wireType, data))
                    offset += length
                }
                0x05 -> { // 32-bit fixed
                    if (offset + 4 > bytes.size) break
                    fields.add(ProtoField(fieldNumber, wireType, bytes.copyOfRange(offset, offset + 4)))
                    offset += 4
                }
                0x01 -> { // 64-bit fixed
                    if (offset + 8 > bytes.size) break
                    fields.add(ProtoField(fieldNumber, wireType, bytes.copyOfRange(offset, offset + 8)))
                    offset += 8
                }
                else -> break // unknown wire type
            }
        }
        return fields
    }

    /** Read a varint from bytes at offset. Returns (value, bytesConsumed). */
    private fun readVarint(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var i = offset
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            i++
            if (b and 0x80 == 0) return result to (i - offset)
            shift += 7
            if (shift >= 35) break // overflow protection
        }
        return 0 to 0
    }

    // --- Protobuf writing primitives ---

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        writeTag(out, fieldNumber, WIRE_VARINT)
        writeVarint(out, value)
    }

    private fun writeString(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        writeBytes(out, fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    private fun writeBytes(out: ByteArrayOutputStream, fieldNumber: Int, data: ByteArray) {
        writeTag(out, fieldNumber, WIRE_LENGTH_DELIMITED)
        writeVarint(out, data.size)
        out.write(data)
    }

    private fun writeTag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        writeVarint(out, (fieldNumber shl 3) or wireType)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /**
     * Build a 6-byte outgoing FD frame header (version + type + LE payload length).
     * Shared by FrontDoorService and FrontDoorReplayStub.
     */
    fun buildOutgoingHeader(payloadLength: Int): ByteArray {
        val h = ByteArray(ClientFrameDecoder.HEADER_SIZE)
        h[0] = ClientFrameDecoder.VERSION
        h[1] = ClientFrameDecoder.TYPE_DATA_FD
        h[2] = (payloadLength and 0xFF).toByte()
        h[3] = ((payloadLength shr 8) and 0xFF).toByte()
        h[4] = ((payloadLength shr 16) and 0xFF).toByte()
        h[5] = ((payloadLength shr 24) and 0xFF).toByte()
        return h
    }

    /**
     * Build a MatchCreated push notification JSON payload.
     * Shared by FrontDoorService and FrontDoorReplayStub.
     */
    fun buildMatchCreatedJson(matchId: String, matchDoorHost: String, matchDoorPort: Int): String = """{"Type":"MatchCreated","MatchInfoV3":{""" +
        """"ControllerFabricUri":"wzmc://forge/$matchId",""" +
        """"MatchEndpointHost":"$matchDoorHost",""" +
        """"MatchEndpointPort":$matchDoorPort,""" +
        """"MatchId":"$matchId",""" +
        """"McFabricId":"wzmc://forge/$matchId",""" +
        """"EventId":"AIBotMatch",""" +
        """"MatchType":"Familiar",""" +
        """"MatchTypeInternal":1,""" +
        """"Battlefield":"FDN",""" +
        """"YourSeat":1,""" +
        """"PlayerInfos":[""" +
        """{"SeatId":1,"TeamId":1,"ScreenName":"ForgePlayer","CosmeticsSelection":{"Avatar":{"Type":"Avatar","Id":"Avatar_Basic_Adventurer"},"Emotes":[]}},""" +
        """{"SeatId":2,"TeamId":2,"ScreenName":"Sparky","CosmeticsSelection":{"Avatar":{"Type":"Avatar","Id":"Avatar_Basic_Sparky"},"Emotes":[]}}""" +
        """]}}"""

    /** CmdType enum values → names (from mtga-internals/docs/fd-envelope-proto.md). */
    private val CMD_TYPE_NAMES = mapOf(
        0 to "Authenticate",
        1 to "StartHook",
        2 to "Scaling_Passthrough",
        5 to "Attach",
        6 to "GetFormats",
        7 to "ForceDetach",
        400 to "Deck_GetDeck",
        401 to "Deck_GetDeckSummaries",
        407 to "Deck_GetDeckSummariesV2",
        550 to "Card_GetCardSet",
        600 to "Event_Join",
        604 to "Event_GetActiveEvents",
        612 to "Event_AiBotMatch",
        613 to "Event_GetActiveMatches",
        624 to "Event_GetActiveEventsV2",
        703 to "Store_GetEntitlements",
        704 to "Carousel_GetCarouselItems",
        800 to "Currency_GetCurrencies",
        901 to "Booster_GetOwnedBoosters",
        1000 to "Quest_GetQuests",
        1100 to "Rank_GetCombinedRankInfo",
        1200 to "PeriodicRewards_GetStatus",
        1520 to "GetVoucherDefinitions",
        1521 to "GetSets",
        1700 to "Graph_GetGraphDefinitions",
        1701 to "Graph_GetGraphState",
        1702 to "Graph_Process",
        1900 to "Cosmetics_GetPlayerOwnedCosmetics",
        1910 to "GetPlayBladeQueueConfig",
        1911 to "GetPlayerPreferences",
        1913 to "LogBusinessEvents",
        2200 to "GetNetDeckFolders",
        2300 to "GetPlayerInbox",
        2400 to "GetDesignerMetadata",
        2500 to "StaticContent",
        2600 to "GetAllPreferredPrintings",
        2700 to "GetAllPrizeWalls",
    )
}
