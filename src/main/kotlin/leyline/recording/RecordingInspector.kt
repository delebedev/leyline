package leyline.recording

import kotlinx.serialization.Serializable
import leyline.LeylinePaths
import java.io.File
import java.util.Base64

/**
 * Filesystem-backed recording inspector used by DebugServer and CLI.
 *
 * Scans session directories under `recordings/<session>/`
 * (or `/tmp/arena-recordings/` fallback). Supports engine dumps, proxy
 * captures, and the always-on events.jsonl paired stream.
 */
object RecordingInspector {

    /** Optional card name resolver — set during server startup when CardRepository is available. */
    var cardNameLookup: ((Int) -> String?)? = null

    private val recordingsRoot = LeylinePaths.RECORDINGS

    @Serializable
    data class SessionRef(
        val id: String,
        val name: String,
        val path: String,
        val mode: String,
        val fileCount: Int,
        val updatedAt: Long,
    )

    @Serializable
    data class Seat(
        val seatId: Int,
        val playerName: String,
        val role: String,
    )

    @Serializable
    data class ActionEvent(
        val seq: Int,
        val file: String,
        val gsId: Int,
        val msgId: Int,
        val turn: Int?,
        val phase: String?,
        val category: String,
        val actorSeat: Int?,
        val actor: String?,
        val instanceId: Int?,
        val grpId: Int?,
        val card: String?,
        val details: Map<String, String>,
    ) {
        val actorLabel: String
            get() = actor ?: actorSeat?.let { "seat-$it" } ?: "?"
    }

    @Serializable
    data class Summary(
        val sessionId: String,
        val path: String,
        val mode: String,
        val fileCount: Int,
        val messageCount: Int,
        val actionCount: Int,
        val firstTurn: Int?,
        val lastTurn: Int?,
        val seats: List<Seat>,
        val topCards: List<CardCount>,
        val actionsByActor: List<ActorCount>,
    )

    @Serializable
    data class CardCount(
        val card: String,
        val count: Int,
    )

    @Serializable
    data class ActorCount(
        val actor: String,
        val count: Int,
    )

    @Serializable
    data class CompareResult(
        val leftSessionId: String,
        val rightSessionId: String,
        val leftActions: Int,
        val rightActions: Int,
        val firstDivergence: Divergence?,
    )

    @Serializable
    data class Divergence(
        val index: Int,
        val left: ActionSignature?,
        val right: ActionSignature?,
    )

    @Serializable
    data class ActionSignature(
        val category: String,
        val actorSeat: Int?,
        val grpId: Int?,
        val card: String?,
    )

    fun listSessions(): List<SessionRef> {
        val dirs = linkedSetOf<File>()

        // Scan session directories under RECORDINGS (timestamped dirs with engine/capture subdirs)
        val root = LeylinePaths.RECORDINGS
        if (root.isDirectory) {
            root.listFiles()
                ?.filter { it.isDirectory && it.name != "latest" }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { sessionDir ->
                    // Add leaf dirs that contain .bin files (engine/, capture/payloads/)
                    // Skip frames/ — raw wire captures with 6-byte headers, not useful in UI
                    sessionDir.walk()
                        .filter { it.isDirectory && it.name != "frames" && RecordingDecoder.listRecordingFiles(it).isNotEmpty() }
                        .forEach { dirs.add(it) }
                }
        }

        val sessions = dirs.mapNotNull { dir ->
            val files = RecordingDecoder.listRecordingFiles(dir)
            if (files.isEmpty()) return@mapNotNull null
            SessionRef(
                id = encodeSessionId(dir),
                name = dir.relativeTo(root).path,
                path = dir.absolutePath,
                mode = detectMode(dir),
                fileCount = files.size,
                updatedAt = files.maxOfOrNull { it.lastModified() } ?: dir.lastModified(),
            )
        }

        return sessions.sortedByDescending { it.updatedAt }
    }

    fun summary(sessionIdOrPath: String): Summary? {
        val dir = resolveSessionDir(sessionIdOrPath) ?: return null
        val files = RecordingDecoder.listRecordingFiles(dir)
        if (files.isEmpty()) return null

        val seats = RecordingDecoder.detectSeats(dir)
        val messages = RecordingDecoder.decodeDirectory(dir, seatFilter = null)
        val actions = extractActionEvents(messages, seats)

        val topCards = actions
            .asSequence()
            .filter { it.category == "PlayLand" || it.category == "CastSpell" }
            .map { it.card ?: it.grpId?.let { id -> "grp:$id" } ?: "unknown" }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(10)
            .map { CardCount(it.key, it.value) }

        val actionsByActor = actions
            .asSequence()
            .map { it.actorLabel }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { ActorCount(it.key, it.value) }

        return Summary(
            sessionId = encodeSessionId(dir),
            path = dir.absolutePath,
            mode = detectMode(dir),
            fileCount = files.size,
            messageCount = messages.size,
            actionCount = actions.size,
            firstTurn = messages.mapNotNull { it.turnInfo?.turn }.filter { it > 0 }.minOrNull(),
            lastTurn = messages.mapNotNull { it.turnInfo?.turn }.filter { it > 0 }.maxOrNull(),
            seats = seats.values
                .sortedBy { it.systemSeatId }
                .map { Seat(it.systemSeatId, it.playerName, it.role) },
            topCards = topCards,
            actionsByActor = actionsByActor,
        )
    }

    fun actions(
        sessionIdOrPath: String,
        cardFilter: String? = null,
        actorFilter: String? = null,
        limit: Int = 1000,
    ): List<ActionEvent> {
        val dir = resolveSessionDir(sessionIdOrPath) ?: return emptyList()
        val seats = RecordingDecoder.detectSeats(dir)
        val messages = RecordingDecoder.decodeDirectory(dir, seatFilter = null)
        val actions = extractActionEvents(messages, seats)

        val cardNeedle = cardFilter?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val actorNeedle = actorFilter?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        return actions
            .asSequence()
            .filter { event ->
                if (cardNeedle != null) {
                    val cardOk = (event.card?.lowercase()?.contains(cardNeedle) == true) ||
                        (event.grpId?.toString()?.contains(cardNeedle) == true)
                    if (!cardOk) return@filter false
                }
                if (actorNeedle != null) {
                    val actorOk = (event.actor?.lowercase()?.contains(actorNeedle) == true) ||
                        (event.actorSeat?.toString() == actorNeedle)
                    if (!actorOk) return@filter false
                }
                true
            }
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    fun messages(sessionIdOrPath: String): List<RecordingDecoder.DecodedMessage>? {
        val dir = resolveSessionDir(sessionIdOrPath) ?: return null
        val files = RecordingDecoder.listRecordingFiles(dir)
        if (files.isEmpty()) return null
        return RecordingDecoder.decodeDirectory(dir, seatFilter = null)
    }

    fun compare(leftSessionIdOrPath: String, rightSessionIdOrPath: String): CompareResult? {
        val left = actions(leftSessionIdOrPath, limit = Int.MAX_VALUE)
        val right = actions(rightSessionIdOrPath, limit = Int.MAX_VALUE)
        if (left.isEmpty() && right.isEmpty()) return null

        val max = maxOf(left.size, right.size)
        var divergence: Divergence? = null

        for (i in 0 until max) {
            val l = left.getOrNull(i)
            val r = right.getOrNull(i)
            if (!sameAction(l, r)) {
                divergence = Divergence(
                    index = i,
                    left = l?.toSignature(),
                    right = r?.toSignature(),
                )
                break
            }
        }

        return CompareResult(
            leftSessionId = encodeOrKeep(leftSessionIdOrPath),
            rightSessionId = encodeOrKeep(rightSessionIdOrPath),
            leftActions = left.size,
            rightActions = right.size,
            firstDivergence = divergence,
        )
    }

    fun resolveSessionDir(sessionIdOrPath: String): File? {
        decodeSessionId(sessionIdOrPath)?.let { decoded ->
            if (decoded.isDirectory) return decoded
        }

        val direct = File(sessionIdOrPath)
        if (direct.isDirectory) return direct
        return null
    }

    /**
     * Walk up from a recording leaf dir (engine/, capture/payloads/) to the session root.
     * The session root is the timestamped dir directly under RECORDINGS that contains
     * engine/, capture/, mode.txt, analysis.json, etc.
     */
    fun resolveSessionRoot(recordingDir: File): File {
        val root = LeylinePaths.RECORDINGS.canonicalFile
        var dir = recordingDir.canonicalFile
        while (dir.parentFile != null && dir.parentFile != root) {
            dir = dir.parentFile
        }
        return dir
    }

    private fun extractActionEvents(
        messages: List<RecordingDecoder.DecodedMessage>,
        seats: Map<Int, RecordingDecoder.SeatInfo>,
    ): List<ActionEvent> {
        val seen = mutableSetOf<String>()
        val events = mutableListOf<ActionEvent>()
        val instanceToGrp = mutableMapOf<Int, Int>()

        for (msg in messages) {
            for (obj in msg.objects) {
                if (obj.grpId != 0) instanceToGrp[obj.instanceId] = obj.grpId
            }

            val fallbackActorSeat = msg.annotations
                .firstOrNull { "UserActionTaken" in it.types && it.affectorId in 1..8 }
                ?.affectorId

            for (ann in msg.annotations) {
                val category = detailAsString(ann.details["category"]) ?: continue
                val actorSeat = ann.affectorId.takeIf { it in 1..8 } ?: fallbackActorSeat
                val actor = actorSeat?.let { seats[it]?.playerName ?: "seat-$it" }
                val instanceId = ann.affectedIds.firstOrNull { it > 0 }

                val grpFromDetails = detailAsInt(ann.details["grpId"])
                val grpId = grpFromDetails ?: instanceId?.let { instanceToGrp[it] }
                val card = grpId?.let { cardNameLookup?.invoke(it) ?: "grp:$it" }

                val key = listOf(
                    msg.msgId,
                    msg.gsId,
                    ann.id,
                    category,
                    actorSeat ?: 0,
                    instanceId ?: 0,
                    grpId ?: 0,
                ).joinToString(":")
                if (!seen.add(key)) continue

                events.add(
                    ActionEvent(
                        seq = events.size + 1,
                        file = msg.file,
                        gsId = msg.gsId,
                        msgId = msg.msgId,
                        turn = msg.turnInfo?.turn,
                        phase = msg.turnInfo?.phase,
                        category = category,
                        actorSeat = actorSeat,
                        actor = actor,
                        instanceId = instanceId,
                        grpId = grpId,
                        card = card,
                        details = ann.details.mapValues { (_, v) -> detailAsString(v) ?: v.toString() },
                    ),
                )
            }
        }

        return events
    }

    private fun detailAsString(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Number -> value.toString()
        is Boolean -> value.toString()
        is List<*> -> value.firstOrNull()?.toString()
        else -> value.toString()
    }

    private fun detailAsInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Double -> value.toInt()
        is String -> value.toIntOrNull()
        is List<*> -> value.firstOrNull()?.toString()?.toIntOrNull()
        else -> null
    }

    private fun ActionEvent.toSignature(): ActionSignature = ActionSignature(
        category = category,
        actorSeat = actorSeat,
        grpId = grpId,
        card = card,
    )

    private fun sameAction(left: ActionEvent?, right: ActionEvent?): Boolean {
        if (left == null || right == null) return left == right
        return left.category == right.category &&
            left.actorSeat == right.actorSeat &&
            left.grpId == right.grpId
    }

    private fun detectMode(dir: File): String = when {
        dir.name == "engine" || dir.absolutePath.contains("/engine") -> "engine"
        dir.name == "payloads" || dir.absolutePath.contains("/capture/") -> "proxy"
        else -> "recording"
    }

    private fun encodeOrKeep(sessionIdOrPath: String): String {
        val file = resolveSessionDir(sessionIdOrPath) ?: return sessionIdOrPath
        return encodeSessionId(file)
    }

    private fun encodeSessionId(dir: File): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(dir.absolutePath.toByteArray(Charsets.UTF_8))

    private fun decodeSessionId(sessionId: String): File? = try {
        val path = String(Base64.getUrlDecoder().decode(sessionId), Charsets.UTF_8)
        File(path)
    } catch (_: Exception) {
        null
    }
}
