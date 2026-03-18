package leyline.recording

import com.google.protobuf.util.JsonFormat
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
 * Decodes binary recordings into structured JSON summaries.
 *
 * Reads both directions:
 * - **S→C** (`MD_S-C_*.bin`): MatchServiceToClientMessage → GREToClientMessage summaries
 * - **C→S** (`MD_C-S_*.bin`): ClientToMatchServiceMessage → ClientToGREMessage summaries
 *
 * Output: JSONL (one JSON object per message, ordered by file sequence number).
 * Each line has `dir: "S-C"` or `dir: "C-S"` to distinguish direction.
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
        val dir: String = "S-C",
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
        val declareAttackers: DeclareAttackersSummary? = null,
        val declareBlockers: DeclareBlockersSummary? = null,
        val hasSelectTargetsReq: Boolean = false,
        val hasIntermissionReq: Boolean = false,
        val castingTimeOptions: List<CastingTimeOptionSummary> = emptyList(),
        val players: List<PlayerSummary> = emptyList(),
        val turnInfo: TurnInfoSummary? = null,
        val promptId: Int? = null,
        val systemSeatIds: List<Int> = emptyList(),
        val edictal: EdictalSummary? = null,
        // Client→Server fields (only set when dir="C-S")
        val clientType: String? = null,
        val clientAttackers: ClientAttackersSummary? = null,
        val clientBlockers: ClientBlockersSummary? = null,
        val clientAction: ClientActionSummary? = null,
        val clientTargets: ClientTargetsSummary? = null,
        val clientSettings: SettingsSummary? = null,
        // GroupReq/GroupResp (surveil, scry, mulligan grouping prompts)
        val groupReq: GroupReqSummary? = null,
        val clientGroupResp: ClientGroupRespSummary? = null,
        // Lossless prompt data — full proto JSON via JsonFormat.printer()
        // Set for prompt messages (DeclareAttackers/Blockers Req/Resp, Submit*, GroupReq/Resp)
        val promptType: String? = null,
        val promptData: JsonElement? = null,
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
        // Combat state — only non-null during combat phases
        val attackState: String? = null,
        val blockState: String? = null,
        val attackInfo: AttackInfoSummary? = null,
        val blockInfo: BlockInfoSummary? = null,
    )

    @Serializable
    data class AttackInfoSummary(
        val targetId: Int,
    )

    @Serializable
    data class BlockInfoSummary(
        val attackerIds: List<Int>,
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
        val shouldStop: Boolean? = null,
        val highlight: String? = null,
        val abilityGrpId: Int? = null,
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

    @Serializable
    data class DeclareAttackersSummary(
        val attackers: List<AttackerSummary>,
        val qualifiedAttackers: List<AttackerSummary> = emptyList(),
        val canSubmitAttackers: Boolean = true,
        val hasRequirements: Boolean = false,
        val hasRestrictions: Boolean = false,
    )

    @Serializable
    data class AttackerSummary(
        val instanceId: Int,
        val mustAttack: Boolean = false,
    )

    @Serializable
    data class DeclareBlockersSummary(
        val blockers: List<BlockerSummary>,
    )

    @Serializable
    data class BlockerSummary(
        val instanceId: Int,
        val attackerInstanceIds: List<Int>,
        val maxAttackers: Int = 0,
    )

    // --- Client→Server summary types ---

    @Serializable
    data class ClientAttackersSummary(
        val selectedAttackers: List<Int>,
        val autoDeclare: Boolean = false,
    )

    @Serializable
    data class ClientBlockersSummary(
        val blockers: List<ClientBlockerAssignment>,
    )

    @Serializable
    data class ClientBlockerAssignment(
        val blockerInstanceId: Int,
        val selectedAttackerInstanceIds: List<Int>,
    )

    @Serializable
    data class ClientActionSummary(
        val actions: List<ActionSummary>,
        val autoPassPriority: String? = null,
        val setYield: String? = null,
        val yieldScope: String? = null,
        val yieldKey: String? = null,
    )

    @Serializable
    data class ClientTargetsSummary(
        val targetIdx: Int = 0,
        val targetInstanceIds: List<Int> = emptyList(),
    )

    @Serializable
    data class GroupReqSummary(
        val instanceIds: List<Int>,
        val groupSpecs: List<GroupSpecSummary>,
        val groupType: String,
        val context: String,
        val sourceId: Int = 0,
    )

    @Serializable
    data class GroupSpecSummary(
        val lowerBound: Int = 0,
        val upperBound: Int = 0,
        val zoneType: String,
        val subZoneType: String? = null,
    )

    @Serializable
    data class ClientGroupRespSummary(
        val groups: List<GroupSummary>,
        val groupType: String,
    )

    @Serializable
    data class GroupSummary(
        val ids: List<Int> = emptyList(),
        val zoneType: String? = null,
        val subZoneType: String? = null,
    )

    @Serializable
    data class SettingsSummary(
        val stops: List<StopSummary> = emptyList(),
        val transientStops: List<StopSummary> = emptyList(),
        val yields: List<YieldSummary> = emptyList(),
        val autoPassOption: String? = null,
        val stackAutoPassOption: String? = null,
        val smartStops: String? = null,
        val clearAllStops: Boolean = false,
        val clearAllYields: Boolean = false,
    )

    @Serializable
    data class StopSummary(
        val stopType: String,
        val scope: String,
        val status: String,
    )

    @Serializable
    data class YieldSummary(
        val abilityGrpId: Int = 0,
        val cardTitleId: Int = 0,
        val scope: String,
        val status: String,
    )

    @Serializable
    data class EdictalSummary(
        val actions: List<ActionSummary> = emptyList(),
        val autoPassPriority: String? = null,
    )

    @Serializable
    data class CastingTimeOptionSummary(
        val ctoId: Int,
        val type: String,
        val affectedId: Int = 0,
        val affectorId: Int = 0,
        val grpId: Int = 0,
        val isRequired: Boolean = false,
        val modal: ModalSummary? = null,
        val selectN: SelectNSummary? = null,
    )

    @Serializable
    data class ModalSummary(
        val abilityGrpId: Int = 0,
        val minSel: Int = 0,
        val maxSel: Int = 0,
        val options: List<ModalOptionSummary>,
    )

    @Serializable
    data class ModalOptionSummary(
        val grpId: Int,
    )

    @Serializable
    data class SelectNSummary(
        val minSel: Int = 0,
        val maxSel: Int = 0,
        val context: String = "",
        val ids: List<Int> = emptyList(),
        val idType: String = "",
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
     * Handles both S→C (MatchServiceToClientMessage with GRE events) and
     * C→S (ClientToMatchServiceMessage with nested ClientToGREMessage payload).
     * @param seatFilter if non-null, only include GRE messages addressed to this seat
     */
    fun decodeFile(file: File, startIndex: Int = 0, seatFilter: Int? = null): List<DecodedMessage> {
        val isClientToServer = file.name.contains("C-S")
        if (isClientToServer) {
            val decoded = decodeClientMessage(file.readBytes(), startIndex, file.name)
                ?: return emptyList()
            return listOf(decoded)
        }

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
    fun listRecordingFiles(dir: File): List<File> {
        val files = listBinFiles(dir)
        if (files.isNotEmpty()) return files
        // Auto-discover capture/payloads/ subdir (proxy recording layout)
        val payloads = File(dir, "capture/payloads")
        if (payloads.isDirectory) return listBinFiles(payloads)
        return emptyList()
    }

    private fun listBinFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { f ->
                if (!f.isFile || f.extension != "bin") return@filter false
                val n = f.name
                // Door-tagged captures: keep MD in both directions
                if (n.contains("MD_") || n.contains("FD_")) {
                    return@filter n.contains("MD_S-C") || n.contains("MD_C-S")
                }
                // Legacy: include both directions
                true
            }
            ?.sortedBy { it.name }
            ?: emptyList()

    // --- Client→Server decoding ---

    /**
     * Decode a ClientToMatchServiceMessage .bin file.
     * Extracts the nested [ClientToGREMessage] from the `payload` bytes field.
     */
    private fun decodeClientMessage(bytes: ByteArray, index: Int, fileName: String): DecodedMessage? {
        val wrapper = parseClientWrapper(bytes) ?: return null
        // Only decode payload as ClientToGREMessage for actual GRE wrappers.
        // Other types (auth, echo, connect) have different payload shapes.
        if (wrapper.clientToMatchServiceMessageType != ClientToMatchServiceMessageType.ClientToGremessage) return null
        if (wrapper.payload.isEmpty) return null
        val gre = try {
            ClientToGREMessage.parseFrom(wrapper.payload)
        } catch (_: Throwable) {
            return null
        }
        val typeName = gre.type.name.strip()
        return DecodedMessage(
            index = index,
            file = fileName,
            dir = "C-S",
            greType = typeName,
            gsId = gre.gameStateId,
            clientType = typeName,
            clientAttackers = gre.takeIf { it.hasDeclareAttackersResp() }?.let { msg ->
                val resp = msg.declareAttackersResp
                ClientAttackersSummary(
                    selectedAttackers = resp.selectedAttackersList.map { it.attackerInstanceId },
                    autoDeclare = resp.autoDeclare,
                )
            },
            clientBlockers = gre.takeIf { it.hasDeclareBlockersResp() }?.let { msg ->
                val resp = msg.declareBlockersResp
                ClientBlockersSummary(
                    blockers = resp.selectedBlockersList.map { b ->
                        ClientBlockerAssignment(
                            blockerInstanceId = b.blockerInstanceId,
                            selectedAttackerInstanceIds = b.selectedAttackerInstanceIdsList.map { it.toInt() },
                        )
                    },
                )
            },
            clientAction = gre.takeIf { it.hasPerformActionResp() }?.let { msg ->
                val resp = msg.performActionResp
                ClientActionSummary(
                    actions = resp.actionsList.map { summarizeAction(it, seatId = null) },
                    autoPassPriority = resp.autoPassPriority.name.strip().takeIf { it != "None" },
                    setYield = resp.setYield.name.strip().takeIf { it != "None" },
                    yieldScope = resp.appliesTo.name.strip().takeIf { it != "None" },
                    yieldKey = resp.mapTo.name.strip().takeIf { it != "None" },
                )
            },
            clientTargets = gre.takeIf { it.hasSelectTargetsResp() }?.let { msg ->
                val resp = msg.selectTargetsResp
                ClientTargetsSummary(
                    targetIdx = resp.target.targetIdx,
                    targetInstanceIds = resp.target.targetsList.map { it.targetInstanceId },
                )
            },
            clientSettings = gre.takeIf { it.hasSetSettingsReq() }?.let { msg ->
                summarizeSettings(msg.setSettingsReq.settings)
            },
            clientGroupResp = gre.takeIf { it.hasGroupResp() }?.groupResp?.let { resp ->
                ClientGroupRespSummary(
                    groups = resp.groupsList.map { g ->
                        GroupSummary(
                            ids = g.idsList.map { it.toInt() },
                            zoneType = g.zoneType.name.strip().takeIf { it != "None" },
                            subZoneType = g.subZoneType.name.strip().takeIf { it != "None" },
                        )
                    },
                    groupType = resp.groupType.name.strip(),
                )
            },
            promptType = when {
                gre.hasDeclareAttackersResp() -> "DeclareAttackersResp"
                gre.hasDeclareBlockersResp() -> "DeclareBlockersResp"
                gre.hasGroupResp() -> "GroupResp"
                else -> null
            },
            promptData = when {
                gre.hasDeclareAttackersResp() -> protoToJsonElement(gre.declareAttackersResp)
                gre.hasDeclareBlockersResp() -> protoToJsonElement(gre.declareBlockersResp)
                gre.hasGroupResp() -> protoToJsonElement(gre.groupResp)
                else -> null
            },
        )
    }

    /** Parse a ClientToMatchServiceMessage from raw or framed bytes. */
    private fun parseClientWrapper(bytes: ByteArray): ClientToMatchServiceMessage? {
        try {
            return ClientToMatchServiceMessage.parseFrom(bytes)
        } catch (_: Throwable) {}
        val payload = extractClientPayload(bytes) ?: return null
        return try {
            ClientToMatchServiceMessage.parseFrom(payload)
        } catch (_: Throwable) {
            null
        }
    }

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
        val groupReq = summarizeGroupReq(gre)

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
            declareAttackers = gre.takeIf { it.hasDeclareAttackersReq() }
                ?.declareAttackersReq?.let { summarizeDeclareAttackers(it) },
            declareBlockers = gre.takeIf { it.hasDeclareBlockersReq() }
                ?.declareBlockersReq?.let { summarizeDeclareBlockers(it) },
            hasSelectTargetsReq = gre.hasSelectTargetsReq(),
            hasIntermissionReq = gre.hasIntermissionReq(),
            castingTimeOptions = gre.takeIf { it.hasCastingTimeOptionsReq() }
                ?.castingTimeOptionsReq?.castingTimeOptionReqList
                ?.map { summarizeCastingTimeOption(it) } ?: emptyList(),
            edictal = summarizeEdictal(gre),
            groupReq = groupReq,
            players = gsm?.playersList?.map { summarizePlayer(it) } ?: emptyList(),
            turnInfo = gsm?.takeIf { it.hasTurnInfo() }?.turnInfo?.let { summarizeTurn(it) },
            promptId = gre.takeIf { it.hasPrompt() && it.prompt.promptId != 0 }?.prompt?.promptId,
            systemSeatIds = gre.systemSeatIdsList.map { it.toInt() },
            promptType = summarizePromptType(gre),
            promptData = summarizePromptData(gre),
        )
    }

    private fun summarizeGroupReq(gre: GREToClientMessage): GroupReqSummary? =
        gre.takeIf { it.hasGroupReq() }?.groupReq?.let { req ->
            GroupReqSummary(
                instanceIds = req.instanceIdsList.map { it.toInt() },
                groupSpecs = req.groupSpecsList.map { spec ->
                    GroupSpecSummary(
                        lowerBound = spec.lowerBound,
                        upperBound = spec.upperBound,
                        zoneType = spec.zoneType.name.strip(),
                        subZoneType = spec.subZoneType.name.strip().takeIf { it != "None" },
                    )
                },
                groupType = req.groupType.name.strip(),
                context = req.context.name.strip(),
                sourceId = req.sourceId,
            )
        }

    private fun summarizePromptType(gre: GREToClientMessage): String? = when {
        gre.hasDeclareAttackersReq() -> "DeclareAttackersReq"
        gre.hasDeclareBlockersReq() -> "DeclareBlockersReq"
        gre.hasGroupReq() -> "GroupReq"
        gre.hasSelectTargetsReq() -> "SelectTargetsReq"
        gre.hasSelectNReq() -> "SelectNReq"
        else -> null
    }

    private fun summarizePromptData(gre: GREToClientMessage): JsonElement? = when {
        gre.hasDeclareAttackersReq() -> protoToJsonElement(gre.declareAttackersReq)
        gre.hasDeclareBlockersReq() -> protoToJsonElement(gre.declareBlockersReq)
        gre.hasGroupReq() -> protoToJsonElement(gre.groupReq)
        gre.hasSelectTargetsReq() -> protoToJsonElement(gre.selectTargetsReq)
        gre.hasSelectNReq() -> protoToJsonElement(gre.selectNReq)
        else -> null
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
        attackState = o.attackState.name.strip().takeIf { it != "None" },
        blockState = o.blockState.name.strip().takeIf { it != "None" },
        attackInfo = o.takeIf { it.hasAttackInfo() }?.attackInfo?.let {
            AttackInfoSummary(targetId = it.targetId)
        },
        blockInfo = o.takeIf { it.hasBlockInfo() }?.blockInfo?.let {
            BlockInfoSummary(attackerIds = it.attackerIdsList.map { id -> id.toInt() })
        },
    )

    private fun summarizeAnnotation(a: AnnotationInfo): AnnotationSummary {
        val details = a.detailsList.associate { d ->
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
            key to value
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

    private fun extractActions(gre: GREToClientMessage): List<ActionSummary> = buildList {
        // Actions from GameStateMessage
        if (gre.hasGameStateMessage()) {
            for (ai in gre.gameStateMessage.actionsList) {
                add(summarizeAction(ai.action, seatId = ai.seatId.toInt()))
            }
        }
        // Actions from ActionsAvailableReq
        if (gre.hasActionsAvailableReq()) {
            for (a in gre.actionsAvailableReq.actionsList) {
                add(summarizeAction(a, seatId = null))
            }
        }
    }

    private fun summarizeAction(a: Action, seatId: Int?): ActionSummary {
        val highlightName = a.highlight.name.strip().takeIf { it != "None" }
        return ActionSummary(
            type = a.actionType.name.strip(),
            instanceId = a.instanceId.toInt(),
            grpId = a.grpId.toInt(),
            seatId = seatId,
            shouldStop = a.shouldStop.takeIf { it },
            highlight = highlightName,
            abilityGrpId = a.abilityGrpId.toInt().takeIf { it != 0 },
        )
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

    private fun summarizeDeclareAttackers(req: DeclareAttackersReq): DeclareAttackersSummary =
        DeclareAttackersSummary(
            attackers = req.attackersList.map {
                AttackerSummary(instanceId = it.attackerInstanceId.toInt(), mustAttack = it.mustAttack)
            },
            qualifiedAttackers = req.qualifiedAttackersList.map {
                AttackerSummary(instanceId = it.attackerInstanceId.toInt(), mustAttack = it.mustAttack)
            },
            canSubmitAttackers = req.canSubmitAttackers,
            hasRequirements = req.hasRequirements,
            hasRestrictions = req.hasRestrictions,
        )

    private fun summarizeDeclareBlockers(req: DeclareBlockersReq): DeclareBlockersSummary =
        DeclareBlockersSummary(
            blockers = req.blockersList.map {
                BlockerSummary(
                    instanceId = it.blockerInstanceId,
                    attackerInstanceIds = it.attackerInstanceIdsList.map { id -> id.toInt() },
                    maxAttackers = it.maxAttackers,
                )
            },
        )

    private fun summarizeCastingTimeOption(cto: CastingTimeOptionReq): CastingTimeOptionSummary =
        CastingTimeOptionSummary(
            ctoId = cto.ctoId.toInt(),
            type = cto.castingTimeOptionType.name.strip(),
            affectedId = cto.affectedId.toInt(),
            affectorId = cto.affectorId.toInt(),
            grpId = cto.grpId.toInt(),
            isRequired = cto.isRequired,
            modal = cto.takeIf { it.hasModalReq() }?.modalReq?.let { m ->
                ModalSummary(
                    abilityGrpId = m.abilityGrpId.toInt(),
                    minSel = m.minSel.toInt(),
                    maxSel = m.maxSel.toInt(),
                    options = m.modalOptionsList.map { ModalOptionSummary(grpId = it.grpId.toInt()) },
                )
            },
            selectN = cto.takeIf { it.hasSelectNReq() }?.selectNReq?.let { s ->
                SelectNSummary(
                    minSel = s.minSel,
                    maxSel = s.maxSel.toInt(),
                    context = s.context.name.strip(),
                    ids = s.idsList.map { it.toInt() },
                    idType = s.idType.name.strip(),
                )
            },
        )

    private fun summarizeSettings(s: SettingsMessage): SettingsSummary = SettingsSummary(
        stops = s.stopsList.map { StopSummary(it.stopType.name.strip(), it.appliesTo.name.strip(), it.status.name.strip()) },
        transientStops = s.transientStopsList.map { StopSummary(it.stopType.name.strip(), it.appliesTo.name.strip(), it.status.name.strip()) },
        yields = s.yieldsList.map {
            YieldSummary(
                abilityGrpId = it.abilityGrpId.toInt(),
                cardTitleId = it.cardTitleId.toInt(),
                scope = it.appliesTo.name.strip(),
                status = it.status.name.strip(),
            )
        },
        autoPassOption = s.autoPassOption.name.strip().takeIf { it != "None" },
        stackAutoPassOption = s.stackAutoPassOption.name.strip().takeIf { it != "None" },
        smartStops = s.smartStopsSetting.name.strip().takeIf { it != "None" && it.isNotEmpty() },
        clearAllStops = s.clearAllStops.name.strip() == "Set",
        clearAllYields = s.clearAllYields.name.strip() == "Set",
    )

    private fun summarizeEdictal(gre: GREToClientMessage): EdictalSummary? {
        if (!gre.hasEdictalMessage()) return null
        val edictal = gre.edictalMessage
        if (!edictal.hasEdictMessage()) return null
        val inner = edictal.edictMessage
        if (!inner.hasPerformActionResp()) return null
        val resp = inner.performActionResp
        return EdictalSummary(
            actions = resp.actionsList.map { summarizeAction(it, seatId = null) },
            autoPassPriority = resp.autoPassPriority.name.strip().takeIf { it != "None" },
        )
    }

    private val jsonPrinter: JsonFormat.Printer = JsonFormat.printer()
        .omittingInsignificantWhitespace()
        .preservingProtoFieldNames()

    private fun protoToJsonElement(msg: com.google.protobuf.Message): JsonElement {
        val jsonStr = jsonPrinter.print(msg)
        return Json.parseToJsonElement(jsonStr)
    }

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
