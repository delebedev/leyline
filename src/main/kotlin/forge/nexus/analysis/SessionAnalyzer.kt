package forge.nexus.analysis

import forge.nexus.conformance.RecordingDecoder
import forge.nexus.debug.NexusPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        val invariantViolations: List<InvariantChecker.Violation> = emptyList(),
        val gsidChain: GsIdChainResult = GsIdChainResult(),
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
        val seen: List<Int> = emptyList(),
        val totalDistinct: Int = 0,
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

        // Decode engine messages
        val engineDir = File(sessionDir, "engine")
        val messages = if (engineDir.isDirectory) {
            RecordingDecoder.decodeDirectory(engineDir, seatFilter = null)
        } else {
            emptyList()
        }

        if (messages.isEmpty()) {
            log.info("No messages to analyze in {}", sessionName)
            return null
        }

        // Duration estimate from first/last message timestamps (file mod times)
        val engineFiles = RecordingDecoder.listRecordingFiles(engineDir)
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

        // --- Invariant checking via proto replay ---
        val checker = InvariantChecker()
        // Convert decoded messages back to GRE protos for invariant checking
        val greMessages = replayGREMessages(engineDir)
        greMessages.forEach { checker.process(it) }

        // --- GsId chain validation ---
        val gsIdViolations = InvariantChecker.validateGsIdChain(greMessages)
        val gsIdChain = GsIdChainResult(
            valid = gsIdViolations.isEmpty(),
            length = greMessages.count { it.hasGameStateMessage() },
            violations = gsIdViolations,
        )

        // --- Mechanic classification from annotations ---
        val mechanics = classifyMechanics(messages)

        // --- Annotation coverage ---
        val allAnnotationTypes = messages
            .flatMap { it.annotations }
            .flatMap { ann -> ann.types.mapNotNull { parseAnnotationType(it) } }
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
            invariantViolations = checker.violations,
            gsidChain = gsIdChain,
            interestingMoments = interestingMoments,
            annotationCoverage = annotationCoverage,
        )

        // Write analysis.json
        try {
            analysisFile.writeText(json.encodeToString(analysis))
            log.info("Analysis written: {} ({} violations, {} mechanics)", analysisFile, checker.violations.size, mechanics.size)
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
     * for invariant checking. Returns empty list on parse failure.
     */
    private fun replayGREMessages(engineDir: File): List<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage> {
        if (!engineDir.isDirectory) return emptyList()
        val result = mutableListOf<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage>()
        val files = RecordingDecoder.listRecordingFiles(engineDir).sortedBy { it.name }

        for (file in files) {
            try {
                val bytes = file.readBytes()
                val matchMsg = RecordingDecoder.parseMatchMessage(bytes) ?: continue
                if (matchMsg.hasGreToClientEvent()) {
                    result.addAll(matchMsg.greToClientEvent.greToClientMessagesList)
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
        val file = NexusPaths.MANIFEST_JSON
        if (!file.exists()) return emptySet()
        return try {
            Json.decodeFromString<Set<String>>(file.readText())
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Update manifest with newly seen mechanics. */
    private fun updateManifest(mechanics: List<MechanicClassifier.MechanicCount>) {
        val file = NexusPaths.MANIFEST_JSON
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
