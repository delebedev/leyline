package leyline.debug

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import leyline.LeylinePaths
import leyline.analysis.MechanicClassifier
import leyline.analysis.SessionAnalyzer
import leyline.game.GameEvent
import leyline.match.MatchRecorder
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Always-on dual-stream recorder. Captures paired forge events + GRE proto
 * summaries into a single JSONL file per session.
 *
 * Three (or four) stream types in events.jsonl:
 * - `proto` — outbound GRE messages (structural summaries, not full bytes)
 * - `forge` — GameEvents drained from GameEventCollector
 * - `client` — inbound client actions
 * - `golden` — real server GRE messages (proxy mode only)
 *
 * Full proto bytes continue to go to engine .bin files via ProtoDump.
 *
 * One SessionRecorder per game session. Created in MatchSession/MatchHandler.
 * Flushes on each write (crash-safe).
 */
class SessionRecorder(
    private val sessionDir: File = LeylinePaths.SESSION_DIR,
    private val mode: String = "engine",
) : MatchRecorder {
    private val log = LoggerFactory.getLogger(SessionRecorder::class.java)
    private val seq = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    var gameOverReceived = false
        private set

    private val json = Json {
        encodeDefaults = true
    }

    private val writer: BufferedWriter? = try {
        sessionDir.mkdirs()
        // Write mode.txt
        File(sessionDir, "mode.txt").writeText(mode)
        BufferedWriter(FileWriter(File(sessionDir, "events.jsonl"), true))
    } catch (e: Exception) {
        log.error("Failed to create JSONL writer: {}", e.message)
        null
    }

    // --- Serializable event entries ---

    @Serializable
    data class ProtoEntry(
        val seq: Int,
        val ts: String,
        val stream: String = "proto",
        val greType: String,
        val msgId: Int = 0,
        val gsId: Int = 0,
        val updateType: String? = null,
        val annotationTypes: List<String> = emptyList(),
        val categories: List<String> = emptyList(),
        val objectCount: Int = 0,
        val zoneIds: List<Int> = emptyList(),
    )

    @Serializable
    data class ForgeEntry(
        val seq: Int,
        val ts: String,
        val stream: String = "forge",
        val events: List<ForgeEventSummary>,
    )

    @Serializable
    data class ForgeEventSummary(
        val type: String,
        val mechanic: String,
        val card: String? = null,
        val seat: Int? = null,
    )

    @Serializable
    data class ClientEntry(
        val seq: Int,
        val ts: String,
        val stream: String = "client",
        val actionType: String,
        val instanceId: Int = 0,
        val grpId: Int = 0,
        val gsId: Int = 0,
    )

    @Serializable
    data class GoldenEntry(
        val seq: Int,
        val ts: String,
        val stream: String = "golden",
        val greType: String,
        val msgId: Int = 0,
        val gsId: Int = 0,
        val updateType: String? = null,
        val annotationTypes: List<String> = emptyList(),
    )

    private fun extractAnnotationTypes(gre: GREToClientMessage): List<String> =
        if (gre.hasGameStateMessage()) {
            gre.gameStateMessage.annotationsList.flatMap { ann ->
                ann.typeList.map { it.name.removeSuffix("_695e").removeSuffix("_aa0d") }
            }
        } else {
            emptyList()
        }

    // --- Recording methods ---

    /** Record outbound GRE messages (what we told the client). */
    override fun recordOutbound(messages: List<GREToClientMessage>) {
        if (closed.get()) return
        val s = seq.incrementAndGet()
        val ts = Instant.now().toString()

        for (gre in messages) {
            val annotationTypes = extractAnnotationTypes(gre)

            val categories = if (gre.hasGameStateMessage()) {
                gre.gameStateMessage.annotationsList.mapNotNull { ann ->
                    ann.detailsList.firstOrNull { it.key == "category" }?.let { kv ->
                        if (kv.valueStringCount > 0) kv.getValueString(0) else null
                    }
                }
            } else {
                emptyList()
            }

            val entry = ProtoEntry(
                seq = s,
                ts = ts,
                greType = gre.type.name.removeSuffix("_695e").removeSuffix("_aa0d"),
                msgId = gre.msgId,
                gsId = if (gre.hasGameStateMessage()) gre.gameStateMessage.gameStateId else gre.gameStateId,
                updateType = if (gre.hasGameStateMessage()) {
                    gre.gameStateMessage.update.name.removeSuffix("_695e").removeSuffix("_aa0d").takeIf { it != "UNRECOGNIZED" }
                } else {
                    null
                },
                annotationTypes = annotationTypes,
                categories = categories,
                objectCount = if (gre.hasGameStateMessage()) gre.gameStateMessage.gameObjectsCount else 0,
                zoneIds = if (gre.hasGameStateMessage()) gre.gameStateMessage.zonesList.map { it.zoneId } else emptyList(),
            )
            writeLine(json.encodeToString(entry))
        }
    }

    /** Record forge-side game events. */
    fun recordForgeEvents(events: List<GameEvent>) {
        if (closed.get() || events.isEmpty()) return
        val s = seq.get() // pair with the most recent proto seq
        val ts = Instant.now().toString()

        val summaries = events.map { ev ->
            ForgeEventSummary(
                type = ev::class.simpleName ?: "Unknown",
                mechanic = MechanicClassifier.classify(ev),
                seat = extractSeat(ev),
            )
        }

        val entry = ForgeEntry(seq = s, ts = ts, events = summaries)
        writeLine(json.encodeToString(entry))
    }

    /** Record inbound client action. */
    override fun recordClientAction(greMsg: ClientToGREMessage) {
        if (closed.get()) return
        val s = seq.incrementAndGet()
        val ts = Instant.now().toString()

        val action = if (greMsg.hasPerformActionResp() && greMsg.performActionResp.actionsCount > 0) {
            greMsg.performActionResp.getActions(0)
        } else {
            null
        }

        val entry = ClientEntry(
            seq = s,
            ts = ts,
            actionType = greMsg.type.name.removeSuffix("_097b").removeSuffix("_aa0d"),
            instanceId = action?.instanceId ?: 0,
            grpId = action?.grpId ?: 0,
            gsId = greMsg.gameStateId,
        )
        writeLine(json.encodeToString(entry))
    }

    /** Record real server GRE messages (proxy mode). */
    fun recordGolden(messages: List<GREToClientMessage>) {
        if (closed.get()) return
        val s = seq.incrementAndGet()
        val ts = Instant.now().toString()

        for (gre in messages) {
            val annotationTypes = extractAnnotationTypes(gre)

            val entry = GoldenEntry(
                seq = s,
                ts = ts,
                greType = gre.type.name.removeSuffix("_695e").removeSuffix("_aa0d"),
                msgId = gre.msgId,
                gsId = if (gre.hasGameStateMessage()) gre.gameStateMessage.gameStateId else gre.gameStateId,
                updateType = if (gre.hasGameStateMessage()) {
                    gre.gameStateMessage.update.name.removeSuffix("_695e").removeSuffix("_aa0d").takeIf { it != "UNRECOGNIZED" }
                } else {
                    null
                },
                annotationTypes = annotationTypes,
            )
            writeLine(json.encodeToString(entry))
        }
    }

    /** Mark game over received. */
    override fun markGameOver() {
        gameOverReceived = true
    }

    /** Close the recorder and unregister from shutdown hook. Idempotent. */
    override fun shutdown() {
        close()
        unregister(this)
    }

    /** Close the recorder and optionally trigger analysis. */
    fun close(triggerAnalysis: Boolean = true) {
        if (!closed.compareAndSet(false, true)) return
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {}

        if (triggerAnalysis) {
            val termination = if (gameOverReceived) "game_over" else "disconnect"
            triggerAnalysis(termination)
        }
    }

    /** Trigger post-game analysis on a background thread. */
    private fun triggerAnalysis(termination: String) {
        Thread({
            try {
                SessionAnalyzer.analyze(sessionDir, termination = termination)
            } catch (e: Exception) {
                log.error("Post-game analysis failed: {}", e.message, e)
            }
        }, "session-analyzer").apply {
            isDaemon = true
            start()
        }
    }

    private fun writeLine(line: String) {
        try {
            writer?.apply {
                write(line)
                newLine()
                flush()
            }
        } catch (e: Exception) {
            log.warn("JSONL write failed: {}", e.message)
        }
    }

    private fun extractSeat(ev: GameEvent): Int? = when (ev) {
        is GameEvent.LandPlayed -> ev.seatId
        is GameEvent.SpellCast -> ev.seatId
        is GameEvent.AttackersDeclared -> ev.seatId
        is GameEvent.BlockersDeclared -> ev.seatId
        is GameEvent.CardDestroyed -> ev.seatId
        is GameEvent.CardSacrificed -> ev.seatId
        is GameEvent.CardBounced -> ev.seatId
        is GameEvent.CardExiled -> ev.seatId
        is GameEvent.CardDiscarded -> ev.seatId
        is GameEvent.CardMilled -> ev.seatId
        is GameEvent.SpellCountered -> ev.seatId
        is GameEvent.TokenCreated -> ev.seatId
        is GameEvent.LifeChanged -> ev.seatId
        is GameEvent.DamageDealtToPlayer -> ev.targetSeatId
        is GameEvent.LibraryShuffled -> ev.seatId
        is GameEvent.Scry -> ev.seatId
        is GameEvent.Surveil -> ev.seatId
        else -> null
    }

    companion object {
        /** Active recorders for shutdown hook cleanup. */
        private val activeRecorders = java.util.concurrent.CopyOnWriteArrayList<SessionRecorder>()

        init {
            Runtime.getRuntime().addShutdownHook(
                Thread({
                    for (recorder in activeRecorders) {
                        try {
                            if (!recorder.closed.get()) {
                                recorder.close(triggerAnalysis = true)
                            }
                        } catch (_: Exception) {}
                    }
                }, "session-recorder-shutdown"),
            )
        }

        /** Register a recorder for shutdown hook cleanup. */
        fun register(recorder: SessionRecorder) {
            activeRecorders.add(recorder)
        }

        /** Unregister a recorder (already closed). */
        fun unregister(recorder: SessionRecorder) {
            activeRecorders.remove(recorder)
        }
    }
}
