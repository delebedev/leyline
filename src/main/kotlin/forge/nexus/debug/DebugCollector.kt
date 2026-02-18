package forge.nexus.debug

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.util.JsonFormat
import forge.nexus.game.CardDb
import forge.nexus.server.MatchHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.iterator

/**
 * Ring-buffer collector for Arena protocol messages. Thread-safe singleton.
 * Powers the debug panel at :8090.
 */
object ArenaDebugCollector {
    private val log = LoggerFactory.getLogger(ArenaDebugCollector::class.java)

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
        val bridges = MatchHandler.defaultRegistry.activeBridges()
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

    /** Instance ID cross-reference table from all active bridges. */
    fun idMap(): List<IdMapEntry> {
        val result = mutableListOf<IdMapEntry>()
        for ((_, bridge) in MatchHandler.defaultRegistry.activeBridges()) {
            val game = bridge.getGame() ?: continue
            val map = bridge.getInstanceIdMap()
            for ((instanceId, forgeCardId) in map) {
                val card = game.findById(forgeCardId)
                result.add(
                    IdMapEntry(
                        instanceId = instanceId,
                        forgeCardId = forgeCardId,
                        cardName = card?.name ?: "?",
                        zone = card?.zone?.zoneType?.name ?: "?",
                        grpId = CardDb.lookupByName(card?.name ?: "") ?: 0,
                    ),
                )
            }
        }
        return result.sortedBy { it.instanceId }
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
        val zone: String,
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

/** Logback appender that feeds log events into [ArenaDebugCollector]. */
class ArenaDebugLogAppender : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        ArenaDebugCollector.recordLog(
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
