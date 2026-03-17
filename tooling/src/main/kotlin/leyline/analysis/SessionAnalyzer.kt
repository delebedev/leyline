package leyline.analysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import leyline.LeylinePaths
import leyline.game.InvariantChecker
import leyline.recording.RecordingDecoder
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Post-game session analyzer. Reads events.jsonl and engine .bin files,
 * runs invariant checks and mechanic classification, writes analysis.json.
 *
 * Called automatically on game end (background thread) or via CLI.
 */
object SessionAnalyzer {

    private val log = LoggerFactory.getLogger(SessionAnalyzer::class.java)

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @Serializable
    data class Analysis(
        val session: String,
        val mode: String,
        val termination: String = "unknown",
        val turns: Int = 0,
        val winner: String = "unknown",
        val durationMs: Long = 0,
        val mechanicsExercised: List<MechanicClassifier.MechanicCount> = emptyList(),
        // TODO: invariant checker has too many false positives (annotation_ref for transient
        //  objects, annotation_seq for seat-filtered gaps). Suppressed until checker is smarter.
        //  Re-enable when we can group by check type and filter known-noisy patterns.
        // val invariantViolations: List<InvariantChecker.Violation> = emptyList(),
        // val gsidChain: GsIdChainResult = GsIdChainResult(),
        val interestingMoments: List<InterestingMoment> = emptyList(),
        val annotationCoverage: AnnotationCoverage = AnnotationCoverage(),
    )

    @Serializable
    data class GsIdChainResult(
        val valid: Boolean = true,
        val length: Int = 0,
        val violations: List<InvariantChecker.Violation> = emptyList(),
    )

    @Serializable
    data class InterestingMoment(
        val seq: Int,
        val reason: String,
        val mechanic: String,
    )

    @Serializable
    data class AnnotationCoverage(
        val seen: List<String> = emptyList(),
        val totalDistinct: Int = 0,
    )

    @Serializable
    data class CardSeen(
        val grpId: Int,
        val name: String = "",
    )

    /**
     * Analyze a session directory and write analysis.json.
     *
     * @param sessionDir session directory (e.g. recordings/2026-02-22_14-30-00/)
     * @param termination how the game ended: "game_over", "disconnect", "shutdown"
     * @param force re-analyze even if analysis.json exists
     * @return the analysis result, or null if session has no data
     */
    fun analyze(
        sessionDir: File,
        termination: String = "unknown",
        force: Boolean = false,
    ): Analysis? {
        val analysisFile = File(sessionDir, "analysis.json")
        if (analysisFile.exists() && !force) {
            log.debug("Analysis already exists: {}", analysisFile)
            return try {
                Json.decodeFromString<Analysis>(analysisFile.readText())
            } catch (_: Exception) {
                null
            }
        }

        val sessionName = sessionDir.name
        log.info("Analyzing session: {}", sessionName)

        // Read mode
        val modeFile = File(sessionDir, "mode.txt")
        val mode = if (modeFile.exists()) modeFile.readText().trim() else "unknown"

        // Decode messages — prefer engine/ dumps, fall back to capture payloads
        val engineDir = File(sessionDir, "engine")
        val captureDir = File(sessionDir, "capture/payloads")
        // New format: seat subdirs under capture/ (e.g. capture/seat-1/md-payloads/)
        val seatPayloadDir = File(sessionDir, "capture").let { capture ->
            capture.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("seat-") }
                ?.sortedBy { it.name }
                ?.map { File(it, "md-payloads") }
                ?.firstOrNull { it.isDirectory && RecordingDecoder.listRecordingFiles(it).isNotEmpty() }
        }
        val sourceDir = when {
            engineDir.isDirectory && RecordingDecoder.listRecordingFiles(engineDir).isNotEmpty() -> engineDir
            captureDir.isDirectory && RecordingDecoder.listRecordingFiles(captureDir).isNotEmpty() -> captureDir
            seatPayloadDir != null -> seatPayloadDir
            else -> null
        }
        // Filter to seat 1 (human player) — engine/ dumps contain both seat copies
        // (primary send + mirrorToFamiliar) so seatFilter=null would double-count gsIds
        val messages = if (sourceDir != null) {
            RecordingDecoder.decodeDirectory(sourceDir, seatFilter = 1)
        } else {
            emptyList()
        }

        if (messages.isEmpty()) {
            log.info("No messages to analyze in {}", sessionName)
            return null
        }

        // Duration estimate from first/last message timestamps (file mod times)
        val engineFiles = RecordingDecoder.listRecordingFiles(sourceDir ?: engineDir)
        val durationMs = if (engineFiles.size >= 2) {
            val first = engineFiles.first().lastModified()
            val last = engineFiles.last().lastModified()
            last - first
        } else {
            0L
        }

        // Turn count
        val turns = messages.mapNotNull { it.turnInfo?.turn }.filter { it > 0 }.maxOrNull() ?: 0

        // Winner detection (from IntermissionReq presence or last game state)
        val winner = detectWinner(messages)

        // TODO: invariant checking + gsId chain validation suppressed (too many false positives).
        //  See Analysis data class for details. Re-enable when checker is smarter.

        // --- Mechanic classification from annotations ---
        val mechanics = classifyMechanics(messages)

        // --- Annotation coverage ---
        val allAnnotationTypes = messages
            .flatMap { it.annotations }
            .flatMap { it.types }
            .distinct()
            .sorted()
        val annotationCoverage = AnnotationCoverage(
            seen = allAnnotationTypes,
            totalDistinct = allAnnotationTypes.size,
        )

        // --- Interesting moments ---
        val interestingMoments = findInterestingMoments(messages, mechanics, sessionDir)

        val analysis = Analysis(
            session = sessionName,
            mode = mode,
            termination = termination,
            turns = turns,
            winner = winner,
            durationMs = durationMs,
            mechanicsExercised = mechanics,
            interestingMoments = interestingMoments,
            annotationCoverage = annotationCoverage,
        )

        // Write analysis.json
        try {
            analysisFile.writeText(json.encodeToString(analysis))
            log.info("Analysis written: {} ({} mechanics)", analysisFile, mechanics.size)
        } catch (e: Exception) {
            log.error("Failed to write analysis.json: {}", e.message)
        }

        // Update manifest
        updateManifest(mechanics)

        return analysis
    }

    /** Read an existing analysis.json, or null. */
    fun readAnalysis(sessionDir: File): Analysis? {
        val analysisFile = File(sessionDir, "analysis.json")
        if (!analysisFile.exists()) return null
        return try {
            Json.decodeFromString<Analysis>(analysisFile.readText())
        } catch (_: Exception) {
            null
        }
    }

    // --- Private helpers ---

    /**
     * Build card index from all objects seen in the session.
     * Uses [allMessages] (no seat filter) to catch both players' cards.
     */
    private fun buildCardIndex(messages: List<RecordingDecoder.DecodedMessage>): List<CardSeen> {
        val grpIds = messages
            .flatMap { it.objects }
            .map { it.grpId }
            .filter { it > 0 }
            .distinct()
            .sorted()
        if (grpIds.isEmpty()) return emptyList()

        val names = resolveCardNames(grpIds)
        return grpIds.map { CardSeen(it, names[it] ?: "") }
    }

    /**
     * Resolve grpIds to card names via Arena's local SQLite DB.
     * Same DB and query pattern as `just card` in lookup.just.
     * Returns empty map if DB not found (CI, no Arena installed).
     */
    private fun resolveCardNames(grpIds: List<Int>): Map<Int, String> {
        val rawDir = File(
            System.getenv("HOME") ?: "/tmp",
            "Library/Application Support/com.wizards.mtga/Downloads/Raw",
        )
        val dbFile = rawDir.listFiles()
            ?.firstOrNull { it.name.startsWith("Raw_CardDatabase_") && it.name.endsWith(".mtga") }
            ?: return emptyMap()

        val inClause = grpIds.joinToString(",")
        val query = "SELECT c.GrpId, l.Loc FROM Cards c " +
            "JOIN Localizations_enUS l ON c.TitleId = l.LocId " +
            "WHERE c.GrpId IN ($inClause);"
        return try {
            val process = ProcessBuilder("sqlite3", dbFile.absolutePath, query)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.lines()
                .filter { it.contains("|") }
                .associate {
                    val parts = it.split("|", limit = 2)
                    parts[0].toInt() to parts[1]
                }
        } catch (e: Exception) {
            log.debug("Card name resolution failed: {}", e.message)
            emptyMap()
        }
    }

    private fun detectWinner(messages: List<RecordingDecoder.DecodedMessage>): String {
        // Look for IntermissionReq (game over indicator)
        val hasIntermission = messages.any { it.hasIntermissionReq }
        if (!hasIntermission) return "incomplete"

        // Try to detect from player status in last messages
        val lastPlayers = messages.lastOrNull { it.players.isNotEmpty() }?.players
        if (lastPlayers != null) {
            for (p in lastPlayers) {
                if (p.life <= 0) return if (p.seat == 1) "ai" else "human"
            }
        }
        return "unknown"
    }

    private fun classifyMechanics(messages: List<RecordingDecoder.DecodedMessage>): List<MechanicClassifier.MechanicCount> {
        // Extract categories from annotations
        val categories = messages
            .flatMap { it.annotations }
            .mapNotNull { ann ->
                val cat = ann.details["category"]
                when (cat) {
                    is String -> cat
                    is List<*> -> cat.firstOrNull()?.toString()
                    else -> cat?.toString()
                }
            }

        val mechanics = categories.map { MechanicClassifier.categoryToMechanic(it) }
        return MechanicClassifier.classifyFromStrings(mechanics)
    }

    /**
     * Replay engine .bin files through protobuf parser to get actual GRE messages
     * for invariant checking. Filters to seat 1 to avoid duplicates from mirror path.
     */
    private fun replayGREMessages(sourceDir: File, seatFilter: Int = 1): List<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage> {
        if (!sourceDir.isDirectory) return emptyList()
        val result = mutableListOf<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage>()
        val files = RecordingDecoder.listRecordingFiles(sourceDir).sortedBy { it.name }

        for (file in files) {
            try {
                val bytes = file.readBytes()
                val matchMsg = RecordingDecoder.parseMatchMessage(bytes) ?: continue
                if (matchMsg.hasGreToClientEvent()) {
                    for (gre in matchMsg.greToClientEvent.greToClientMessagesList) {
                        val seats = gre.systemSeatIdsList.map { it.toInt() }
                        if (seats.isNotEmpty() && seatFilter !in seats) continue
                        result.add(gre)
                    }
                }
            } catch (_: Exception) {
                // Skip unparseable files
            }
        }
        return result
    }

    private fun parseAnnotationType(typeName: String): Int? {
        // Annotation type names are like "AnnotationType_CardPlayed_1234" — extract the number
        val match = Regex("_(\\d+)$").find(typeName)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun findInterestingMoments(
        messages: List<RecordingDecoder.DecodedMessage>,
        mechanics: List<MechanicClassifier.MechanicCount>,
        sessionDir: File,
    ): List<InterestingMoment> {
        val moments = mutableListOf<InterestingMoment>()
        val manifest = readManifest()
        val seenMechanics = mutableSetOf<String>()

        for (msg in messages) {
            for (ann in msg.annotations) {
                val cat = ann.details["category"]
                val catStr = when (cat) {
                    is String -> cat
                    is List<*> -> cat.firstOrNull()?.toString()
                    else -> cat?.toString()
                } ?: continue

                val mechanic = MechanicClassifier.categoryToMechanic(catStr)
                if (seenMechanics.add(mechanic) && mechanic !in manifest) {
                    moments.add(
                        InterestingMoment(
                            seq = msg.index,
                            reason = "first '$mechanic' in recorded sessions",
                            mechanic = mechanic,
                        ),
                    )
                }
            }
        }
        return moments
    }

    /** Read the cross-session mechanic manifest. */
    fun readManifest(): Set<String> {
        val file = LeylinePaths.MANIFEST_JSON
        if (!file.exists()) return emptySet()
        return try {
            Json.decodeFromString<Set<String>>(file.readText())
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Update manifest with newly seen mechanics. */
    private fun updateManifest(mechanics: List<MechanicClassifier.MechanicCount>) {
        val file = LeylinePaths.MANIFEST_JSON
        val existing = readManifest().toMutableSet()
        existing.addAll(mechanics.map { it.type })
        try {
            file.parentFile?.mkdirs()
            file.writeText(Json.encodeToString(existing.sorted()))
        } catch (e: Exception) {
            log.warn("Failed to update manifest: {}", e.message)
        }
    }
}
