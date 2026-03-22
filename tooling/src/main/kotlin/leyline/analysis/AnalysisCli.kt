package leyline.analysis

import leyline.LeylinePaths
import leyline.recording.RecordingDecoder
import leyline.recording.RecordingInspector
import java.io.File
import kotlin.system.exitProcess

/**
 * CLI entry points for recording analysis commands.
 * Invoked via justfile targets (rec-analyze, rec-violations, etc.).
 */
private val inspector = RecordingInspector()

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println(
            "Usage: analyze <session> [--force] | analyze-all | find <keyword> | " +
                "annotation-contract <session> <type> [effectId] | violations [session] | " +
                "mechanics | latest | gre-types [--unhandled]",
        )
        exitProcess(1)
    }

    when (args[0]) {
        "analyze" -> cmdAnalyze(args.drop(1))
        "analyze-all" -> cmdAnalyzeAll(args.drop(1))
        "find" -> cmdFind(args.drop(1))
        "annotation-contract" -> cmdAnnotationContract(args.drop(1))
        "violations" -> cmdViolations(args.drop(1))
        "mechanics" -> cmdMechanics()
        "latest" -> cmdLatest()
        "gre-types" -> cmdGreTypes(args.drop(1))
        else -> {
            System.err.println("Unknown command: ${args[0]}")
            exitProcess(1)
        }
    }
}

private fun cmdAnalyze(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: analyze <session> [--force]")
        exitProcess(1)
    }
    val force = "--force" in args
    val sessionArg = args.first { it != "--force" }
    val sessionDir = resolveSession(sessionArg) ?: return

    val analysis = SessionAnalyzer.analyze(sessionDir, force = force)
    if (analysis == null) {
        println("No messages to analyze in ${sessionDir.name}")
        return
    }

    printAnalysisSummary(analysis)
}

private fun cmdAnalyzeAll(args: List<String>) {
    val force = "--force" in args
    val root = LeylinePaths.RECORDINGS
    if (!root.isDirectory) {
        println("No recordings directory found at ${root.absolutePath}")
        return
    }

    val sessions = root.listFiles()
        ?.filter { it.isDirectory && it.name != "latest" }
        ?.sortedByDescending { it.name }
        ?: emptyList()

    var analyzed = 0
    var skipped = 0
    for (sessionDir in sessions) {
        val analysisFile = File(sessionDir, "analysis.json")
        if (analysisFile.exists() && !force) {
            skipped++
            continue
        }
        val analysis = SessionAnalyzer.analyze(sessionDir, force = force)
        if (analysis != null) {
            println(
                "  ${sessionDir.name}: ${analysis.mechanicsExercised.size} mechanics, " +
                    "${analysis.annotationCoverage.totalDistinct} annotation types",
            )
            analyzed++
        }
    }
    println("Analyzed $analyzed sessions ($skipped already had analysis.json)")
}

private fun cmdViolations(args: List<String>) {
    val sessionDir = if (args.isNotEmpty()) {
        resolveSession(args[0])
    } else {
        resolveLatest()
    }
    if (sessionDir == null) return

    val analysis = SessionAnalyzer.readAnalysis(sessionDir)
        ?: SessionAnalyzer.analyze(sessionDir)
    if (analysis == null) {
        println("No analysis available for ${sessionDir.name}")
        return
    }

    println("${sessionDir.name}: invariant reporting currently suppressed in analysis.json")
}

private fun cmdMechanics() {
    val manifest = SessionAnalyzer.readManifest()
    if (manifest.isEmpty()) {
        println("No mechanic manifest found. Play some games first.")
        return
    }
    println("Cross-session mechanic coverage (${manifest.size} types):")
    for (m in manifest.sorted()) {
        println("  $m")
    }
}

private fun cmdLatest() {
    val sessionDir = resolveLatest()
    if (sessionDir == null) return

    // Print summary
    val summary = inspector.summary(sessionDir.absolutePath)
    if (summary != null) {
        println("Session: ${sessionDir.name}")
        println("  mode: ${summary.mode}")
        println("  messages: ${summary.messageCount}  actions: ${summary.actionCount}")
        println("  turns: ${summary.firstTurn ?: "-"} → ${summary.lastTurn ?: "-"}")
        println("  seats: ${summary.seats.joinToString(", ") { "seat ${it.seatId}=${it.playerName}" }}")
        println("  top cards: ${summary.topCards.take(5).joinToString(", ") { "${it.card} x${it.count}" }}")
    }

    // Print analysis
    val analysis = SessionAnalyzer.readAnalysis(sessionDir)
        ?: SessionAnalyzer.analyze(sessionDir)
    if (analysis != null) {
        println()
        printAnalysisSummary(analysis)
    } else {
        println("\nNo analysis available (no engine messages)")
    }
}

private fun cmdFind(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: find <keyword>  — searches card names across all analyzed sessions")
        exitProcess(1)
    }
    val keyword = args.joinToString(" ")
    val root = LeylinePaths.RECORDINGS
    if (!root.isDirectory) {
        println("No recordings directory")
        return
    }

    val sessions = root.listFiles()
        ?.filter { it.isDirectory && it.name != "latest" }
        ?.sortedByDescending { it.name }
        ?: emptyList()

    var hits = 0
    for (sessionDir in sessions) {
        val analysis = SessionAnalyzer.readAnalysis(sessionDir) ?: continue
        val matches = analysis.mechanicsExercised.filter {
            it.type.contains(keyword, ignoreCase = true)
        }
        if (matches.isNotEmpty()) {
            hits++
            val mode = analysis.mode.padEnd(6)
            println("${sessionDir.name}  $mode  T${analysis.turns}  ${matches.joinToString(", ") { "${it.type} x${it.count}" }}")
        }
    }
    if (hits == 0) {
        println("No sessions with mechanics matching '$keyword'.")
    } else {
        println("\n$hits session(s) found.")
    }
}

private fun cmdAnnotationContract(args: List<String>) {
    if (args.size < 2) {
        System.err.println("Usage: annotation-contract <session> <type> [effectId]")
        System.err.println("  e.g.: annotation-contract 09-33-05 LayeredEffect 7007")
        exitProcess(1)
    }
    val sessionDir = resolveSession(args[0]) ?: return
    val typeName = args[1]
    val effectId = args.getOrNull(2)?.toIntOrNull()

    val result = AnnotationContract.extract(sessionDir, typeName, effectId)
    if (result == null) {
        println("No instances of '$typeName' found in ${sessionDir.name}")
        return
    }

    print(AnnotationContract.format(result))
}

// GRE types we currently handle — both inbound (client→server dispatch in MatchHandler)
// and outbound (server→client types we produce). Stripped proto names (no _XXXX suffix).
private val HANDLED_GRE_TYPES = setOf(
    // S→C: types our server produces
    "GameStateMessage",
    "ActionsAvailableReq",
    "MulliganReq",
    "DeclareAttackersReq",
    "DeclareBlockersReq",
    "SelectTargetsReq",
    "SelectNReq",
    "GroupReq",
    "CastingTimeOptionsReq",
    "ConnectResp",
    "TimerStateMessage",
    "IntermissionReq",
    // C→S: types MatchHandler dispatches
    "ConnectReq",
    "ChooseStartingPlayerResp",
    "MulliganResp",
    "GroupResp",
    "SetSettingsReq",
    "ConcedeReq",
    "PerformActionResp",
    "DeclareAttackersResp",
    "SubmitAttackersReq",
    "DeclareBlockersResp",
    "SubmitBlockersReq",
    "SelectTargetsResp",
    "SubmitTargetsReq",
    "CancelActionReq",
    "SelectNresp",
    "CastingTimeOptionsResp",
    "CheckpointReq",
    "Uimessage",
)

private fun cmdGreTypes(args: List<String>) {
    val unhandledOnly = "--unhandled" in args
    val root = LeylinePaths.RECORDINGS
    if (!root.isDirectory) {
        println("No recordings directory found at ${root.absolutePath}")
        return
    }

    val sessions = root.listFiles()
        ?.filter { it.isDirectory && it.name != "latest" }
        ?.sortedByDescending { it.name }
        ?: emptyList()

    // type → (totalCount, sessionNames)
    val stats = mutableMapOf<String, Pair<Int, MutableSet<String>>>()

    for (sessionDir in sessions) {
        val sourceDir = resolveSourceDir(sessionDir) ?: continue
        val messages = try {
            RecordingDecoder.decodeDirectory(sourceDir)
        } catch (e: Exception) {
            System.err.println("  skip ${sessionDir.name}: ${e.message}")
            continue
        }
        for (msg in messages) {
            val type = msg.greType
            val (count, sessionSet) = stats.getOrPut(type) { 0 to mutableSetOf() }
            stats[type] = (count + 1) to sessionSet.also { it.add(sessionDir.name) }
        }
    }

    if (stats.isEmpty()) {
        println("No GRE messages found across recordings.")
        return
    }

    val entries = stats.entries
        .map { (type, pair) -> Triple(type, pair.first, pair.second.size) }
        .let { list -> if (unhandledOnly) list.filter { it.first !in HANDLED_GRE_TYPES } else list }
        .sortedByDescending { it.second }

    if (entries.isEmpty()) {
        println("All GRE types are handled!")
        return
    }

    val typeWidth = maxOf(28, entries.maxOf { it.first.length } + 2)
    println("%-${typeWidth}s %6s %8s %7s".format("GRE Type", "Count", "Sessions", "Handled"))
    for ((type, count, sessionCount) in entries) {
        val handledStr = if (type in HANDLED_GRE_TYPES) "yes" else "NO"
        println("%-${typeWidth}s %6d %8d %7s".format(type, count, sessionCount, handledStr))
    }
    println("\n${entries.size} type(s), ${sessions.size} session(s) scanned")
}

/** Resolve the best source directory for decoding a session's MD payloads. */
private fun resolveSourceDir(sessionDir: File): File? {
    val engineDir = File(sessionDir, "engine")
    if (engineDir.isDirectory && RecordingDecoder.listRecordingFiles(engineDir).isNotEmpty()) {
        return engineDir
    }
    val captureDir = File(sessionDir, "capture/payloads")
    if (captureDir.isDirectory && RecordingDecoder.listRecordingFiles(captureDir).isNotEmpty()) {
        return captureDir
    }
    // New format: seat subdirs
    val capture = File(sessionDir, "capture")
    return capture.listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("seat-") }
        ?.sortedBy { it.name }
        ?.map { File(it, "md-payloads") }
        ?.firstOrNull { it.isDirectory && RecordingDecoder.listRecordingFiles(it).isNotEmpty() }
}

private fun printAnalysisSummary(analysis: SessionAnalyzer.Analysis) {
    println("Analysis: ${analysis.session}")
    println("  mode: ${analysis.mode}  termination: ${analysis.termination}")
    println("  turns: ${analysis.turns}  winner: ${analysis.winner}  duration: ${analysis.durationMs / 1000}s")
    println("  annotation coverage: ${analysis.annotationCoverage.totalDistinct} types")
    if (analysis.mechanicsExercised.isNotEmpty()) {
        println("  mechanics: ${analysis.mechanicsExercised.joinToString(", ") { "${it.type} x${it.count}" }}")
    }
    if (analysis.interestingMoments.isNotEmpty()) {
        println("  interesting:")
        for (m in analysis.interestingMoments) {
            println("    seq=${m.seq}: ${m.reason}")
        }
    }
}

private fun resolveSession(arg: String): File? {
    // Try direct path
    val direct = File(arg)
    if (direct.isDirectory) return direct

    // Try as session name under recordings root
    val underRoot = File(LeylinePaths.RECORDINGS, arg)
    if (underRoot.isDirectory) return underRoot

    // Try via base64 session id
    val resolved = inspector.resolveSessionDir(arg)
    if (resolved != null) return resolved

    System.err.println("Session not found: $arg")
    return null
}

private fun resolveLatest(): File? {
    val latest = File(LeylinePaths.RECORDINGS, "latest")
    val target = if (latest.exists()) {
        latest.canonicalFile
    } else {
        // Fall back to most recent timestamped dir
        LeylinePaths.RECORDINGS.listFiles()
            ?.filter { it.isDirectory && it.name != "latest" }
            ?.maxByOrNull { it.name }
    }
    if (target == null || !target.isDirectory) {
        System.err.println("No recording sessions found")
        return null
    }
    return target
}
