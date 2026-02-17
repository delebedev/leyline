package forge.nexus.conformance

import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File

/**
 * Decodes Arena binary recordings into structured JSON summaries.
 *
 * Reads S-C_MATCH_DATA_*.bin files (MatchServiceToClientMessage protos),
 * extracts every GREToClientMessage, and produces a structured summary
 * for each one — zones, objects, annotations, actions, turnInfo, etc.
 *
 * Output: JSONL (one JSON object per GREToClientMessage, ordered by file + position).
 */
object RecordingDecoder {

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
        val files = dir.listFiles()
            ?.filter { it.name.startsWith("S-C_MATCH_DATA") && it.name.endsWith(".bin") }
            ?.sortedBy { it.name }
            ?: return emptyMap()

        val seats = mutableMapOf<Int, SeatInfo>()

        // Strategy 1: MatchGameRoomStateChangedEvent (richest info)
        for (file in files) {
            val msg = try {
                MatchServiceToClientMessage.parseFrom(file.readBytes())
            } catch (_: Exception) {
                continue
            }
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
            val msg = try {
                MatchServiceToClientMessage.parseFrom(file.readBytes())
            } catch (_: Exception) {
                continue
            }
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

    data class DecodedMessage(
        val index: Int,
        val file: String,
        val greType: String,
        val msgId: Int,
        val gsId: Int,
        val gsmType: String?,
        val updateType: String?,
        val prevGsId: Int?,
        val zones: List<ZoneSummary>,
        val objects: List<ObjectSummary>,
        val annotations: List<AnnotationSummary>,
        val persistentAnnotations: List<AnnotationSummary>,
        val diffDeletedInstanceIds: List<Int>,
        val diffDeletedPersistentAnnotationIds: List<Int>,
        val actions: List<ActionSummary>,
        val hasActionsAvailableReq: Boolean,
        val hasMulliganReq: Boolean,
        val hasDeclareAttackersReq: Boolean,
        val hasDeclareBlockersReq: Boolean,
        val hasSelectTargetsReq: Boolean,
        val hasIntermissionReq: Boolean,
        val players: List<PlayerSummary>,
        val turnInfo: TurnInfoSummary?,
        val promptId: Int?,
        val systemSeatIds: List<Int>,
    )

    data class ZoneSummary(
        val zoneId: Int,
        val type: String,
        val owner: Int,
        val visibility: String,
        val objectIds: List<Int>,
        val viewers: List<Int>,
    )

    data class ObjectSummary(
        val instanceId: Int,
        val grpId: Int,
        val zoneId: Int,
        val type: String,
        val visibility: String,
        val owner: Int,
        val controller: Int,
        val isTapped: Boolean,
        val hasSummoningSickness: Boolean,
        val power: Int?,
        val toughness: Int?,
        val viewers: List<Int>,
        val superTypes: List<String>,
        val cardTypes: List<String>,
        val subtypes: List<String>,
        val uniqueAbilityCount: Int,
    )

    data class AnnotationSummary(
        val id: Int,
        val types: List<String>,
        val affectorId: Int,
        val affectedIds: List<Int>,
        val details: Map<String, Any>,
    )

    data class ActionSummary(
        val type: String,
        val instanceId: Int,
        val grpId: Int,
        val seatId: Int?,
    )

    data class PlayerSummary(
        val seat: Int,
        val life: Int,
        val status: String,
    )

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
        val files = dir.listFiles()
            ?.filter { it.name.startsWith("S-C_MATCH_DATA") && it.name.endsWith(".bin") }
            ?.sortedBy { it.name }
            ?: return emptyList()

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
        val msg = try {
            MatchServiceToClientMessage.parseFrom(file.readBytes())
        } catch (_: Exception) {
            return emptyList()
        }
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

    /** Serialize a DecodedMessage to a JSON string (manual, no kotlinx dependency needed). */
    fun toJsonLine(msg: DecodedMessage): String = buildString {
        append("{")
        append("\"index\":${msg.index}")
        append(",\"file\":${jsonStr(msg.file)}")
        append(",\"greType\":${jsonStr(msg.greType)}")
        if (msg.msgId != 0) append(",\"msgId\":${msg.msgId}")
        if (msg.gsId != 0) append(",\"gsId\":${msg.gsId}")
        if (msg.gsmType != null) append(",\"gsmType\":${jsonStr(msg.gsmType)}")
        if (msg.updateType != null) append(",\"updateType\":${jsonStr(msg.updateType)}")
        if (msg.prevGsId != null) append(",\"prevGsId\":${msg.prevGsId}")
        if (msg.systemSeatIds.isNotEmpty()) append(",\"systemSeatIds\":${jsonArr(msg.systemSeatIds)}")

        if (msg.turnInfo != null) {
            val t = msg.turnInfo
            append(",\"turnInfo\":{\"phase\":${jsonStr(t.phase)},\"step\":${jsonStr(t.step)},\"turn\":${t.turn}")
            append(",\"activePlayer\":${t.activePlayer},\"priorityPlayer\":${t.priorityPlayer}")
            append(",\"decisionPlayer\":${t.decisionPlayer}}")
        }

        if (msg.players.isNotEmpty()) {
            append(",\"players\":[")
            msg.players.forEachIndexed { i, p ->
                if (i > 0) append(",")
                append("{\"seat\":${p.seat},\"life\":${p.life},\"status\":${jsonStr(p.status)}}")
            }
            append("]")
        }

        if (msg.zones.isNotEmpty()) {
            append(",\"zones\":[")
            msg.zones.forEachIndexed { i, z ->
                if (i > 0) append(",")
                append("{\"zoneId\":${z.zoneId},\"type\":${jsonStr(z.type)},\"owner\":${z.owner}")
                append(",\"visibility\":${jsonStr(z.visibility)},\"objectIds\":${jsonArr(z.objectIds)}")
                if (z.viewers.isNotEmpty()) append(",\"viewers\":${jsonArr(z.viewers)}")
                append("}")
            }
            append("]")
        }

        if (msg.objects.isNotEmpty()) {
            append(",\"objects\":[")
            msg.objects.forEachIndexed { i, o ->
                if (i > 0) append(",")
                append("{\"instanceId\":${o.instanceId},\"grpId\":${o.grpId},\"zoneId\":${o.zoneId}")
                append(",\"type\":${jsonStr(o.type)},\"visibility\":${jsonStr(o.visibility)}")
                append(",\"owner\":${o.owner},\"controller\":${o.controller}")
                if (o.isTapped) append(",\"isTapped\":true")
                if (o.hasSummoningSickness) append(",\"hasSummoningSickness\":true")
                if (o.power != null) append(",\"power\":${o.power}")
                if (o.toughness != null) append(",\"toughness\":${o.toughness}")
                if (o.viewers.isNotEmpty()) append(",\"viewers\":${jsonArr(o.viewers)}")
                if (o.superTypes.isNotEmpty()) append(",\"superTypes\":${jsonStrArr(o.superTypes)}")
                if (o.cardTypes.isNotEmpty()) append(",\"cardTypes\":${jsonStrArr(o.cardTypes)}")
                if (o.subtypes.isNotEmpty()) append(",\"subtypes\":${jsonStrArr(o.subtypes)}")
                if (o.uniqueAbilityCount > 0) append(",\"uniqueAbilityCount\":${o.uniqueAbilityCount}")
                append("}")
            }
            append("]")
        }

        if (msg.annotations.isNotEmpty()) {
            append(",\"annotations\":[")
            msg.annotations.forEachIndexed { i, a ->
                if (i > 0) append(",")
                appendAnnotation(a)
            }
            append("]")
        }

        if (msg.persistentAnnotations.isNotEmpty()) {
            append(",\"persistentAnnotations\":[")
            msg.persistentAnnotations.forEachIndexed { i, a ->
                if (i > 0) append(",")
                appendAnnotation(a)
            }
            append("]")
        }

        if (msg.diffDeletedInstanceIds.isNotEmpty()) {
            append(",\"diffDeletedInstanceIds\":${jsonArr(msg.diffDeletedInstanceIds)}")
        }
        if (msg.diffDeletedPersistentAnnotationIds.isNotEmpty()) {
            append(",\"diffDeletedPersistentAnnotationIds\":${jsonArr(msg.diffDeletedPersistentAnnotationIds)}")
        }

        if (msg.actions.isNotEmpty()) {
            append(",\"actions\":[")
            msg.actions.forEachIndexed { i, a ->
                if (i > 0) append(",")
                append("{\"type\":${jsonStr(a.type)}")
                if (a.instanceId != 0) append(",\"instanceId\":${a.instanceId}")
                if (a.grpId != 0) append(",\"grpId\":${a.grpId}")
                if (a.seatId != null) append(",\"seatId\":${a.seatId}")
                append("}")
            }
            append("]")
        }

        if (msg.hasActionsAvailableReq) append(",\"hasActionsAvailableReq\":true")
        if (msg.hasMulliganReq) append(",\"hasMulliganReq\":true")
        if (msg.hasDeclareAttackersReq) append(",\"hasDeclareAttackersReq\":true")
        if (msg.hasDeclareBlockersReq) append(",\"hasDeclareBlockersReq\":true")
        if (msg.hasSelectTargetsReq) append(",\"hasSelectTargetsReq\":true")
        if (msg.hasIntermissionReq) append(",\"hasIntermissionReq\":true")
        if (msg.promptId != null) append(",\"promptId\":${msg.promptId}")

        append("}")
    }

    private fun StringBuilder.appendAnnotation(a: AnnotationSummary) {
        append("{\"id\":${a.id},\"types\":${jsonStrArr(a.types)}")
        if (a.affectorId != 0) append(",\"affectorId\":${a.affectorId}")
        if (a.affectedIds.isNotEmpty()) append(",\"affectedIds\":${jsonArr(a.affectedIds)}")
        if (a.details.isNotEmpty()) {
            append(",\"details\":{")
            var first = true
            for ((k, v) in a.details) {
                if (!first) append(",")
                first = false
                append("${jsonStr(k)}:${jsonValue(v)}")
            }
            append("}")
        }
        append("}")
    }

    private fun jsonStr(s: String): String = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    private fun jsonArr(ints: List<Int>): String = ints.joinToString(",", "[", "]")
    private fun jsonStrArr(strs: List<String>): String = strs.joinToString(",", "[", "]") { jsonStr(it) }

    @Suppress("UNCHECKED_CAST")
    private fun jsonValue(v: Any): String = when (v) {
        is String -> jsonStr(v)
        is Number -> v.toString()
        is Boolean -> v.toString()
        is List<*> -> {
            val list = v as List<Any>
            if (list.isEmpty()) {
                "[]"
            } else {
                list.joinToString(",", "[", "]") { jsonValue(it) }
            }
        }
        else -> jsonStr(v.toString())
    }
}
