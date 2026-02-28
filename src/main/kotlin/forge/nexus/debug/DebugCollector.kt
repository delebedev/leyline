package forge.nexus.debug

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.util.JsonFormat
import forge.nexus.game.CardDb
import forge.nexus.game.GameBridge
import forge.nexus.game.mapper.ZoneIds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.iterator

/**
 * Ring-buffer collector for client protocol messages. Thread-safe singleton.
 * Powers the debug panel at :8090.
 */
object NexusDebugCollector {
    private val log = LoggerFactory.getLogger(NexusDebugCollector::class.java)

    /**
     * Provider for active bridges. Set during server startup to avoid
     * a direct dependency on the server package.
     */
    var bridgeProvider: (() -> Map<String, GameBridge>)? = null

    private const val MAX_ENTRIES = 500
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)
    private var seq = 0

    private val jsonPrinter: JsonFormat.Printer = JsonFormat.printer()
        .omittingInsignificantWhitespace()
        .preservingProtoFieldNames()

    private val sseJson = Json { encodeDefaults = true }

    @Serializable
    data class Entry(
        val seq: Int,
        val ts: Long,
        val dir: String, // "in" | "out"
        val type: String, // GRE message type name
        val seatId: Int,
        val gsId: Int,
        val json: String,
        val instanceIds: List<Int>,
        val summary: String,
    )

    /** Record an inbound client→server message. */
    fun recordInbound(msg: ClientToMatchServiceMessage) {
        try {
            val greMsg = if (msg.clientToMatchServiceMessageType == ClientToMatchServiceMessageType.ClientToGremessage) {
                ClientToGREMessage.parseFrom(msg.payload)
            } else {
                null
            }

            val type = greMsg?.type?.name?.removeSuffix("_097b")
                ?: msg.clientToMatchServiceMessageType.name.removeSuffix("_f487")
            val seatId = greMsg?.systemSeatId ?: 0
            val gsId = greMsg?.gameStateId ?: 0
            val ids = extractInboundInstanceIds(greMsg)
            val summary = buildInboundSummary(msg, greMsg)
            val json = if (greMsg != null) safeToJson(greMsg) else safeToJson(msg)

            add(Entry(0, System.currentTimeMillis(), "in", type, seatId, gsId, json, ids, summary))
        } catch (e: Exception) {
            log.debug("recordInbound failed: {}", e.message)
        }
    }

    /** Record outbound server→client GRE messages (from sendBundledGRE). */
    fun recordOutbound(messages: List<GREToClientMessage>, seatId: Int) {
        for (gre in messages) {
            try {
                val type = gre.type.name.removeSuffix("_695e")
                val gsId = gre.gameStateId
                val ids = extractOutboundInstanceIds(gre)
                val summary = buildOutboundSummary(gre)
                val json = safeToJson(gre)

                add(Entry(0, System.currentTimeMillis(), "out", type, seatId, gsId, json, ids, summary))
            } catch (e: Exception) {
                log.debug("recordOutbound failed: {}", e.message)
            }
        }
    }

    /** Current message sequence number (for cross-referencing with other timelines). */
    fun currentSeq(): Int = synchronized(buffer) { seq }

    /** Return entries with seq > sinceSeq. */
    fun snapshot(sinceSeq: Int): List<Entry> = synchronized(buffer) {
        buffer.filter { it.seq > sinceSeq }
    }

    /** Current match state summary. */
    fun matchState(): MatchStateSnapshot {
        val bridges = bridgeProvider?.invoke() ?: emptyMap()
        if (bridges.isEmpty()) return MatchStateSnapshot()

        val first = bridges.entries.first()
        val matchId = first.key
        val bridge = first.value
        val game = bridge.getGame()
        return MatchStateSnapshot(
            matchId = matchId,
            phase = game?.phaseHandler?.phase?.name,
            turn = game?.phaseHandler?.turn ?: 0,
            activePlayer = game?.phaseHandler?.playerTurn?.name,
            gameOver = game?.isGameOver ?: false,
            entryCount = synchronized(buffer) { buffer.size },
        )
    }

    /**
     * Instance ID cross-reference table from all active bridges.
     *
     * Shows active vs retired status, proto zone (where the protocol placed it)
     * vs Forge zone (where the engine thinks the card is now). Active = current
     * instanceId for a forgeCardId; retired = old instanceId now in Limbo.
     */
    fun idMap(): List<IdMapEntry> = (bridgeProvider?.invoke() ?: emptyMap())
        .flatMap { (_, bridge) ->
            val game = bridge.getGame() ?: return@flatMap emptyList()
            val allIds = bridge.getInstanceIdMap() // instanceId → forgeCardId (all)
            val activeIds = bridge.getActiveInstanceIdMap() // forgeCardId → active instanceId
            val limboSet = bridge.getLimboSet() // retired instanceIds
            val protoZones = bridge.getProtoZones() // instanceId → proto zoneId

            // Invert activeIds for O(1) lookup: active instanceId → forgeCardId
            val activeInstanceIds = activeIds.values.toSet()

            // Derive seat from player: seat 1 = human, seat 2 = AI
            val seatByPlayerId = mapOf(
                bridge.getPlayer(1)?.id to 1,
                bridge.getPlayer(2)?.id to 2,
            )

            allIds.map { (instanceId, forgeCardId) ->
                val card = game.findById(forgeCardId)
                val isActive = instanceId in activeInstanceIds
                val isLimbo = instanceId in limboSet
                val protoZoneId = protoZones[instanceId]

                IdMapEntry(
                    instanceId = instanceId,
                    forgeCardId = forgeCardId,
                    cardName = card?.name ?: "?",
                    ownerSeatId = seatByPlayerId[card?.owner?.id] ?: 0,
                    status = when {
                        isActive -> "active"
                        isLimbo -> "limbo"
                        else -> "stale" // in reverse map but not active, not yet in limbo
                    },
                    forgeZone = card?.zone?.zoneType?.name ?: "?",
                    protoZone = protoZoneId?.let { protoZoneName(it) },
                    protoZoneId = protoZoneId,
                    grpId = CardDb.lookupByName(card?.name ?: "") ?: 0,
                )
            }
        }
        .sortedBy { it.instanceId }

    /** Human-readable name for a proto zone ID. */
    private fun protoZoneName(zoneId: Int): String = when (zoneId) {
        ZoneIds.BATTLEFIELD -> "Battlefield"
        ZoneIds.STACK -> "Stack"
        ZoneIds.EXILE -> "Exile"
        ZoneIds.LIMBO -> "Limbo"
        ZoneIds.COMMAND -> "Command"
        ZoneIds.P1_HAND -> "Hand(P1)"
        ZoneIds.P1_LIBRARY -> "Library(P1)"
        ZoneIds.P1_GRAVEYARD -> "Graveyard(P1)"
        ZoneIds.P2_HAND -> "Hand(P2)"
        ZoneIds.P2_LIBRARY -> "Library(P2)"
        ZoneIds.P2_GRAVEYARD -> "Graveyard(P2)"
        ZoneIds.P1_SIDEBOARD -> "Sideboard(P1)"
        ZoneIds.P2_SIDEBOARD -> "Sideboard(P2)"
        ZoneIds.REVEALED_P1 -> "Revealed(P1)"
        ZoneIds.REVEALED_P2 -> "Revealed(P2)"
        ZoneIds.SUPPRESSED -> "Suppressed"
        ZoneIds.PENDING -> "Pending"
        else -> "Zone($zoneId)"
    }

    @Serializable
    data class MatchStateSnapshot(
        val matchId: String? = null,
        val phase: String? = null,
        val turn: Int = 0,
        val activePlayer: String? = null,
        val gameOver: Boolean = false,
        val entryCount: Int = 0,
    )

    @Serializable
    data class IdMapEntry(
        val instanceId: Int,
        val forgeCardId: Int,
        val cardName: String,
        /** 1 = human, 2 = AI. */
        val ownerSeatId: Int,
        /** "active" = current instanceId for this card, "limbo" = retired, "stale" = old but not yet limbo'd. */
        val status: String,
        /** Where Forge engine thinks the card is NOW (same for all instanceIds of a card). */
        val forgeZone: String,
        /** Where the protocol last placed THIS specific instanceId (null if never tracked). */
        val protoZone: String? = null,
        val protoZoneId: Int? = null,
        val grpId: Int,
    )

    /** Clear all protocol message and log buffers (for match reset). */
    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            seq = 0
        }
        synchronized(logBuffer) {
            logBuffer.clear()
            logSeq = 0
        }
    }

    // --- Internal ---

    private fun add(entry: Entry) {
        val numbered: Entry
        synchronized(buffer) {
            seq++
            numbered = entry.copy(seq = seq)
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(numbered)
        }
        try {
            DebugEventBus.emit("message", sseJson.encodeToString(numbered))
        } catch (e: Exception) {
            log.debug("Failed to emit SSE message event", e)
        }
    }

    private fun safeToJson(msg: MessageOrBuilder): String = try {
        jsonPrinter.print(msg)
    } catch (_: Exception) {
        "{\"error\":\"proto_to_json_failed\"}"
    }

    private fun extractInboundInstanceIds(gre: ClientToGREMessage?): List<Int> {
        if (gre == null) return emptyList()
        val ids = mutableSetOf<Int>()
        if (gre.hasPerformActionResp()) {
            gre.performActionResp.actionsList.forEach { a ->
                if (a.instanceId != 0) ids.add(a.instanceId)
            }
        }
        if (gre.hasDeclareAttackersResp()) {
            gre.declareAttackersResp.selectedAttackersList.forEach { a ->
                ids.add(a.attackerInstanceId)
            }
        }
        if (gre.hasDeclareBlockersResp()) {
            gre.declareBlockersResp.selectedBlockersList.forEach { b ->
                ids.add(b.blockerInstanceId)
                b.selectedAttackerInstanceIdsList.forEach { ids.add(it) }
            }
        }
        return ids.toList()
    }

    private fun extractOutboundInstanceIds(gre: GREToClientMessage): List<Int> {
        val ids = mutableSetOf<Int>()
        if (gre.hasGameStateMessage()) {
            val gs = gre.gameStateMessage
            gs.gameObjectsList.forEach { ids.add(it.instanceId) }
            gs.zonesList.forEach { z -> z.objectInstanceIdsList.forEach { ids.add(it) } }
        }
        if (gre.hasActionsAvailableReq()) {
            gre.actionsAvailableReq.actionsList.forEach { a ->
                if (a.instanceId != 0) ids.add(a.instanceId)
            }
        }
        if (gre.hasDeclareAttackersReq()) {
            gre.declareAttackersReq.attackersList.forEach { a ->
                ids.add(a.attackerInstanceId)
            }
        }
        return ids.toList()
    }

    private fun buildInboundSummary(msg: ClientToMatchServiceMessage, gre: ClientToGREMessage?): String {
        if (gre == null) return msg.clientToMatchServiceMessageType.name.removeSuffix("_f487")
        val type = gre.type.name.removeSuffix("_097b")
        return when {
            gre.hasPerformActionResp() -> {
                val action = gre.performActionResp.actionsList.firstOrNull()
                if (action != null) {
                    "$type ${action.actionType.name.removeSuffix("_add3")} iid=${action.instanceId}"
                } else {
                    type
                }
            }
            gre.hasMulliganResp() -> "$type decision=${gre.mulliganResp.decision}"
            gre.hasDeclareAttackersResp() -> "$type ${gre.declareAttackersResp.selectedAttackersCount} attackers"
            gre.hasDeclareBlockersResp() -> "$type ${gre.declareBlockersResp.selectedBlockersCount} blockers"
            else -> type
        }
    }

    private fun buildOutboundSummary(gre: GREToClientMessage): String {
        val type = gre.type.name.removeSuffix("_695e")
        return when {
            gre.hasGameStateMessage() -> {
                val gs = gre.gameStateMessage
                val ti = gs.turnInfo
                "$type ${gs.type} phase=${ti.phase.name.removeSuffix("_a549")} objects=${gs.gameObjectsCount}"
            }
            gre.hasActionsAvailableReq() -> {
                val counts = gre.actionsAvailableReq.actionsList.groupBy { it.actionType }
                    .map { (t, v) -> "${t.name.removeSuffix("_add3")}=${v.size}" }
                    .joinToString(" ")
                "$type $counts"
            }
            gre.hasDeclareAttackersReq() -> "$type ${gre.declareAttackersReq.attackersCount} eligible"
            gre.hasDeclareBlockersReq() -> "$type ${gre.declareBlockersReq.blockersCount} eligible"
            else -> type
        }
    }

    // --- Log streaming ---

    private const val MAX_LOG_ENTRIES = 2000
    private val logBuffer = ArrayDeque<LogEntry>(MAX_LOG_ENTRIES)
    private var logSeq = 0

    @Serializable
    data class LogEntry(
        val seq: Int,
        val ts: Long,
        val level: String,
        val logger: String,
        val message: String,
        val thread: String,
    )

    internal fun recordLog(ts: Long, level: String, logger: String, message: String, thread: String) {
        val entry: LogEntry
        synchronized(logBuffer) {
            logSeq++
            entry = LogEntry(logSeq, ts, level, logger, message, thread)
            if (logBuffer.size >= MAX_LOG_ENTRIES) logBuffer.removeFirst()
            logBuffer.addLast(entry)
        }
        try {
            DebugEventBus.emit("log", sseJson.encodeToString(entry))
        } catch (e: Exception) {
            log.debug("Failed to emit SSE log event", e)
        }
    }

    fun logSnapshot(sinceSeq: Int, minLevel: String?): List<LogEntry> {
        val levelOrd = when (minLevel?.uppercase()) {
            "ERROR" -> 4
            "WARN" -> 3
            "INFO" -> 2
            "DEBUG" -> 1
            "TRACE" -> 0
            else -> 1
        }
        return synchronized(logBuffer) {
            logBuffer.filter { it.seq > sinceSeq && levelOrdinal(it.level) >= levelOrd }
        }
    }

    private fun levelOrdinal(level: String) = when (level) {
        "ERROR" -> 4
        "WARN" -> 3
        "INFO" -> 2
        "DEBUG" -> 1
        else -> 0
    }
}

/** Logback appender that feeds log events into [NexusDebugCollector]. */
class NexusDebugLogAppender : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        NexusDebugCollector.recordLog(
            ts = event.timeStamp,
            level = event.level.toString(),
            logger = event.loggerName.substringAfterLast('.'),
            message = event.formattedMessage ?: "",
            thread = event.threadName ?: "",
        )
    }
}

/**
 * Pub/sub bus for real-time debug events (SSE).
 * Collectors emit typed events; [DebugServer] SSE endpoint subscribes.
 */
object DebugEventBus {
    private val log = LoggerFactory.getLogger(DebugEventBus::class.java)
    private val listeners = CopyOnWriteArrayList<(String, String) -> Unit>()

    fun addListener(listener: (String, String) -> Unit) {
        listeners.add(listener)
    }
    fun removeListener(listener: (String, String) -> Unit) {
        listeners.remove(listener)
    }

    fun emit(type: String, data: String) {
        for (l in listeners) {
            try {
                l(type, data)
            } catch (e: Exception) {
                log.debug("SSE listener dispatch failed", e)
            }
        }
    }
}
