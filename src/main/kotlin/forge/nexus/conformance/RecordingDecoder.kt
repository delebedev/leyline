package forge.nexus.conformance

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File

/**
 * Decodes client binary recordings into structured JSON summaries.
 *
 * Reads S-C_MATCH_DATA_*.bin files (MatchServiceToClientMessage protos),
 * extracts every GREToClientMessage, and produces a structured summary
 * for each one — zones, objects, annotations, actions, turnInfo, etc.
 *
 * Output: JSONL (one JSON object per GREToClientMessage, ordered by file + position).
 */
object RecordingDecoder {

    private const val ARENA_HEADER_SIZE = 6
    private const val MAX_FRAME_PAYLOAD = 1_048_576

    /** Seat identity extracted from MatchGameRoomStateChangedEvent. */
    data class SeatInfo(
        val systemSeatId: Int,
        val playerName: String,
        val isBot: Boolean,
    ) {
        /** Human-readable role label. */
        val role: String get() = if (isBot) "AI" else "player"
    }

    /**
     * Scan MATCH_DATA files for seat identities.
     *
     * Strategy (in order):
     * 1. MatchGameRoomStateChangedEvent — has playerName, isBotPlayer, systemSeatId
     * 2. Fallback: enumerate distinct systemSeatIds from GRE messages
     *
     * Returns map of systemSeatId -> SeatInfo.
     */
    fun detectSeats(dir: File): Map<Int, SeatInfo> {
        val files = listRecordingFiles(dir)
        if (files.isEmpty()) return emptyMap()

        val seats = mutableMapOf<Int, SeatInfo>()

        // Strategy 1: MatchGameRoomStateChangedEvent (richest info)
        for (file in files) {
            val msg = parseMatchMessage(file.readBytes()) ?: continue
            if (msg.hasMatchGameRoomStateChangedEvent()) {
                val room = msg.matchGameRoomStateChangedEvent.gameRoomInfo
                for (p in room.playersList) {
                    if (p.systemSeatId > 0) {
                        // isBotPlayer is sometimes unset for Sparky; heuristic fallback
                        val isBot = p.isBotPlayer ||
                            p.playerName.equals("Sparky", ignoreCase = true) ||
                            p.playerName.endsWith("_Familiar")
                        seats[p.systemSeatId] = SeatInfo(
                            systemSeatId = p.systemSeatId,
                            playerName = p.playerName,
                            isBot = isBot,
                        )
                    }
                }
                if (seats.isNotEmpty()) return seats
            }
        }

        // Strategy 2: enumerate seats from GRE systemSeatIds
        val seenSeats = mutableSetOf<Int>()
        for (file in files) {
            val msg = parseMatchMessage(file.readBytes()) ?: continue
            if (!msg.hasGreToClientEvent()) continue
            for (gre in msg.greToClientEvent.greToClientMessagesList) {
                for (sid in gre.systemSeatIdsList) {
                    seenSeats.add(sid.toInt())
                }
            }
        }
        for (sid in seenSeats.sorted()) {
            seats[sid] = SeatInfo(
                systemSeatId = sid,
                playerName = "seat-$sid",
                isBot = false, // unknown without room state
            )
        }
        return seats
    }

    private val PROTO_SUFFIX = Regex("_[a-f0-9]{3,4}$")
    private fun String.strip(): String = replace(PROTO_SUFFIX, "")

    @Serializable
    data class DecodedMessage(
        val index: Int,
        val file: String,
        val greType: String,
        val msgId: Int = 0,
        val gsId: Int = 0,
        val gsmType: String? = null,
        val updateType: String? = null,
        val prevGsId: Int? = null,
        val zones: List<ZoneSummary> = emptyList(),
        val objects: List<ObjectSummary> = emptyList(),
        val annotations: List<AnnotationSummary> = emptyList(),
        val persistentAnnotations: List<AnnotationSummary> = emptyList(),
        val diffDeletedInstanceIds: List<Int> = emptyList(),
        val diffDeletedPersistentAnnotationIds: List<Int> = emptyList(),
        val actions: List<ActionSummary> = emptyList(),
        val hasActionsAvailableReq: Boolean = false,
        val hasMulliganReq: Boolean = false,
        val hasDeclareAttackersReq: Boolean = false,
        val hasDeclareBlockersReq: Boolean = false,
        val hasSelectTargetsReq: Boolean = false,
        val hasIntermissionReq: Boolean = false,
        val players: List<PlayerSummary> = emptyList(),
        val turnInfo: TurnInfoSummary? = null,
        val promptId: Int? = null,
        val systemSeatIds: List<Int> = emptyList(),
    )

    @Serializable
    data class ZoneSummary(
        val zoneId: Int,
        val type: String,
        val owner: Int,
        val visibility: String,
        val objectIds: List<Int>,
        val viewers: List<Int> = emptyList(),
    )

    @Serializable
    data class ObjectSummary(
        val instanceId: Int,
        val grpId: Int,
        val zoneId: Int,
        val type: String,
        val visibility: String,
        val owner: Int,
        val controller: Int,
        val isTapped: Boolean = false,
        val hasSummoningSickness: Boolean = false,
        val power: Int? = null,
        val toughness: Int? = null,
        val viewers: List<Int> = emptyList(),
        val superTypes: List<String> = emptyList(),
        val cardTypes: List<String> = emptyList(),
        val subtypes: List<String> = emptyList(),
        val uniqueAbilityCount: Int = 0,
    )

    @Serializable
    data class AnnotationSummary(
        val id: Int,
        val types: List<String>,
        val affectorId: Int = 0,
        val affectedIds: List<Int> = emptyList(),
        @Serializable(with = AnyMapSerializer::class)
        val details: Map<String, Any> = emptyMap(),
    )

    @Serializable
    data class ActionSummary(
        val type: String,
        val instanceId: Int = 0,
        val grpId: Int = 0,
        val seatId: Int? = null,
    )

    @Serializable
    data class PlayerSummary(
        val seat: Int,
        val life: Int,
        val status: String,
    )

    @Serializable
    data class TurnInfoSummary(
        val phase: String,
        val step: String,
        val turn: Int,
        val activePlayer: Int,
        val priorityPlayer: Int,
        val decisionPlayer: Int,
    )

    /**
     * Decode all S-C_MATCH_DATA_*.bin files in a directory.
     * @param seatFilter if non-null, only include GRE messages addressed to this seat
     */
    fun decodeDirectory(dir: File, seatFilter: Int? = null): List<DecodedMessage> {
        val files = listRecordingFiles(dir)
        if (files.isEmpty()) return emptyList()

        var index = 0
        return files.flatMap { file ->
            decodeFile(file, index, seatFilter).also { index += it.size }
        }
    }

    /**
     * Decode a single .bin file into DecodedMessage list.
     * @param seatFilter if non-null, only include GRE messages addressed to this seat
     */
    fun decodeFile(file: File, startIndex: Int = 0, seatFilter: Int? = null): List<DecodedMessage> {
        val msg = parseMatchMessage(file.readBytes()) ?: return emptyList()
        if (!msg.hasGreToClientEvent()) return emptyList()

        var idx = startIndex
        val result = mutableListOf<DecodedMessage>()
        for (gre in msg.greToClientEvent.greToClientMessagesList) {
            val seatIds = gre.systemSeatIdsList.map { it.toInt() }
            if (seatFilter != null && seatIds.isNotEmpty() && seatFilter !in seatIds) {
                continue // skip messages not addressed to this seat
            }
            result.add(decodeGRE(gre, idx++, file.name))
        }
        return result
    }

    /**
     * Return candidate .bin files from a recording directory.
     *
     * Supports:
     * - Legacy proxy capture names (`S-C_MATCH_DATA_*.bin`)
     * - Engine dump names (`001-GameStateMessage+....bin`)
     * - Door-tagged proxy captures (`*_MD_S-C_*.bin`) — excludes FD and C→S
     */
    fun listRecordingFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { f ->
                if (!f.isFile || f.extension != "bin") return@filter false
                val n = f.name
                // Door-tagged captures: only keep MD S→C
                if (n.contains("MD_") || n.contains("FD_")) {
                    return@filter n.contains("MD_S-C")
                }
                // Legacy: exclude C→S
                if (n.startsWith("C-S_")) return@filter false
                true
            }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Parse a MatchServiceToClientMessage from either:
     * - raw protobuf payload bytes
     * - client framed bytes (6-byte header + payload)
     */
    fun parseMatchMessage(bytes: ByteArray): MatchServiceToClientMessage? {
        parseRaw(bytes)?.let { return it }
        val payload = extractClientPayload(bytes) ?: return null
        return parseRaw(payload)
    }

    private fun parseRaw(bytes: ByteArray): MatchServiceToClientMessage? = try {
        MatchServiceToClientMessage.parseFrom(bytes)
    } catch (_: Throwable) {
        null
    }

    private fun extractClientPayload(bytes: ByteArray): ByteArray? {
        if (bytes.size <= ARENA_HEADER_SIZE) return null
        if (bytes[0].toInt() != 0x04) return null

        val payloadLen =
            (bytes[2].toInt() and 0xff) or
                ((bytes[3].toInt() and 0xff) shl 8) or
                ((bytes[4].toInt() and 0xff) shl 16) or
                ((bytes[5].toInt() and 0xff) shl 24)

        if (payloadLen <= 0 || payloadLen > MAX_FRAME_PAYLOAD) return null
        if (bytes.size < ARENA_HEADER_SIZE + payloadLen) return null
        return bytes.copyOfRange(ARENA_HEADER_SIZE, ARENA_HEADER_SIZE + payloadLen)
    }

    /** Decode a single GREToClientMessage. */
    fun decodeGRE(gre: GREToClientMessage, index: Int, fileName: String): DecodedMessage {
        val gsm = if (gre.hasGameStateMessage()) gre.gameStateMessage else null

        return DecodedMessage(
            index = index,
            file = fileName,
            greType = gre.type.name.strip(),
            msgId = gre.msgId,
            gsId = gre.gameStateId,
            gsmType = gsm?.type?.name?.strip(),
            updateType = gsm?.update?.name?.strip(),
            prevGsId = gsm?.takeIf { it.prevGameStateId != 0 }?.prevGameStateId,
            zones = gsm?.zonesList?.map { summarizeZone(it) } ?: emptyList(),
            objects = gsm?.gameObjectsList?.map { summarizeObject(it) } ?: emptyList(),
            annotations = gsm?.annotationsList?.map { summarizeAnnotation(it) } ?: emptyList(),
            persistentAnnotations = gsm?.persistentAnnotationsList?.map { summarizeAnnotation(it) } ?: emptyList(),
            diffDeletedInstanceIds = gsm?.diffDeletedInstanceIdsList ?: emptyList(),
            diffDeletedPersistentAnnotationIds = gsm?.diffDeletedPersistentAnnotationIdsList ?: emptyList(),
            actions = extractActions(gre),
            hasActionsAvailableReq = gre.hasActionsAvailableReq(),
            hasMulliganReq = gre.hasMulliganReq(),
            hasDeclareAttackersReq = gre.hasDeclareAttackersReq(),
            hasDeclareBlockersReq = gre.hasDeclareBlockersReq(),
            hasSelectTargetsReq = gre.hasSelectTargetsReq(),
            hasIntermissionReq = gre.hasIntermissionReq(),
            players = gsm?.playersList?.map { summarizePlayer(it) } ?: emptyList(),
            turnInfo = gsm?.takeIf { it.hasTurnInfo() }?.turnInfo?.let { summarizeTurn(it) },
            promptId = gre.takeIf { it.hasPrompt() && it.prompt.promptId != 0 }?.prompt?.promptId,
            systemSeatIds = gre.systemSeatIdsList.map { it.toInt() },
        )
    }

    private fun summarizeZone(z: ZoneInfo): ZoneSummary = ZoneSummary(
        zoneId = z.zoneId,
        type = z.type.name.strip(),
        owner = z.ownerSeatId,
        visibility = z.visibility.name.strip(),
        objectIds = z.objectInstanceIdsList.map { it.toInt() },
        viewers = z.viewersList.map { it.toInt() },
    )

    private fun summarizeObject(o: GameObjectInfo): ObjectSummary = ObjectSummary(
        instanceId = o.instanceId,
        grpId = o.grpId,
        zoneId = o.zoneId,
        type = o.type.name.strip(),
        visibility = o.visibility.name.strip(),
        owner = o.ownerSeatId,
        controller = o.controllerSeatId,
        isTapped = o.isTapped,
        hasSummoningSickness = o.hasSummoningSickness,
        power = o.takeIf { it.hasPower() }?.power?.value,
        toughness = o.takeIf { it.hasToughness() }?.toughness?.value,
        viewers = o.viewersList.map { it.toInt() },
        superTypes = o.superTypesList.map { it.name.strip() },
        cardTypes = o.cardTypesList.map { it.name.strip() },
        subtypes = o.subtypesList.map { it.name.strip() },
        uniqueAbilityCount = o.uniqueAbilitiesCount,
    )

    private fun summarizeAnnotation(a: AnnotationInfo): AnnotationSummary {
        val details = mutableMapOf<String, Any>()
        for (d in a.detailsList) {
            val key = d.key
            val value: Any = when (d.type) {
                KeyValuePairValueType.Int32 -> d.valueInt32List.map { it.toInt() }.singleOrList()
                KeyValuePairValueType.Uint32 -> d.valueUint32List.map { it.toInt() }.singleOrList()
                KeyValuePairValueType.String -> d.valueStringList.singleOrList()
                KeyValuePairValueType.Bool -> d.valueBoolList.singleOrList()
                KeyValuePairValueType.Uint64 -> d.valueUint64List.singleOrList()
                KeyValuePairValueType.Int64 -> d.valueInt64List.singleOrList()
                KeyValuePairValueType.Float -> d.valueFloatList.singleOrList()
                KeyValuePairValueType.Double -> d.valueDoubleList.singleOrList()
                else -> "?"
            }
            details[key] = value
        }
        return AnnotationSummary(
            id = a.id,
            types = a.typeList.map { it.name.strip() },
            affectorId = a.affectorId,
            affectedIds = a.affectedIdsList.map { it.toInt() },
            details = details,
        )
    }

    private fun <T> List<T>.singleOrList(): Any = if (size == 1) first() as Any else this

    private fun extractActions(gre: GREToClientMessage): List<ActionSummary> {
        val actions = mutableListOf<ActionSummary>()
        // Actions from GameStateMessage
        if (gre.hasGameStateMessage()) {
            for (ai in gre.gameStateMessage.actionsList) {
                actions.add(
                    ActionSummary(
                        type = ai.action.actionType.name.strip(),
                        instanceId = ai.action.instanceId.toInt(),
                        grpId = ai.action.grpId.toInt(),
                        seatId = ai.seatId.toInt(),
                    ),
                )
            }
        }
        // Actions from ActionsAvailableReq
        if (gre.hasActionsAvailableReq()) {
            for (a in gre.actionsAvailableReq.actionsList) {
                actions.add(
                    ActionSummary(
                        type = a.actionType.name.strip(),
                        instanceId = a.instanceId.toInt(),
                        grpId = a.grpId.toInt(),
                        seatId = null,
                    ),
                )
            }
        }
        return actions
    }

    private fun summarizePlayer(p: PlayerInfo): PlayerSummary = PlayerSummary(
        seat = p.systemSeatNumber,
        life = p.lifeTotal,
        status = p.status.name.strip(),
    )

    private fun summarizeTurn(t: TurnInfo): TurnInfoSummary = TurnInfoSummary(
        phase = t.phase.name.strip(),
        step = t.step.name.strip(),
        turn = t.turnNumber,
        activePlayer = t.activePlayer,
        priorityPlayer = t.priorityPlayer,
        decisionPlayer = t.decisionPlayer,
    )

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    /** Serialize a DecodedMessage to a JSON string. */
    fun toJsonLine(msg: DecodedMessage): String = json.encodeToString(msg)
}

/** Serializer for Map<String, Any> where values are primitives or lists of primitives. */
internal object AnyMapSerializer : KSerializer<Map<String, Any>> {
    private val delegate = MapSerializer(String.serializer(), JsonElement.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Map<String, Any>) {
        (encoder as JsonEncoder).encodeJsonElement(
            buildJsonObject { for ((k, v) in value) put(k, v.toJsonElement()) },
        )
    }

    override fun deserialize(decoder: Decoder): Map<String, Any> =
        throw UnsupportedOperationException("Deserialization not supported")
}

private fun Any.toJsonElement(): JsonElement = when (this) {
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is List<*> -> JsonArray(mapNotNull { (it as? Any)?.toJsonElement() })
    else -> JsonPrimitive(toString())
}
