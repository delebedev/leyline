package forge.nexus.analysis

import forge.nexus.NexusPaths
import forge.nexus.recording.RecordingInspector
import java.io.File
import kotlin.system.exitProcess

/**
 * CLI entry points for recording analysis commands.
 * Invoked via justfile targets (rec-analyze, rec-violations, etc.).
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: analyze <session> [--force] | analyze-all | violations [session] | mechanics | latest")
        exitProcess(1)
    }

    when (args[0]) {
        "analyze" -> cmdAnalyze(args.drop(1))
        "analyze-all" -> cmdAnalyzeAll()
        "violations" -> cmdViolations(args.drop(1))
        "mechanics" -> cmdMechanics()
        "latest" -> cmdLatest()
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

private fun cmdAnalyzeAll() {
    val root = NexusPaths.RECORDINGS
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
        if (analysisFile.exists()) {
            skipped++
            continue
        }
        val analysis = SessionAnalyzer.analyze(sessionDir)
        if (analysis != null) {
            println("  ${sessionDir.name}: ${analysis.mechanicsExercised.size} mechanics, ${analysis.invariantViolations.size} violations")
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

    if (analysis.invariantViolations.isEmpty()) {
        println("${sessionDir.name}: no invariant violations")
        return
    }

    println("${sessionDir.name}: ${analysis.invariantViolations.size} violation(s)")
    for (v in analysis.invariantViolations) {
        println("  seq=${v.seq} gs=${v.gsId} [${v.check}] ${v.message}")
    }

    // Also show gsId chain violations
    if (!analysis.gsidChain.valid) {
        println("\ngsId chain violations:")
        for (v in analysis.gsidChain.violations) {
            println("  seq=${v.seq} [${v.check}] ${v.message}")
        }
    }
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
    val summary = RecordingInspector.summary(sessionDir.absolutePath)
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

private fun printAnalysisSummary(analysis: SessionAnalyzer.Analysis) {
    println("Analysis: ${analysis.session}")
    println("  mode: ${analysis.mode}  termination: ${analysis.termination}")
    println("  turns: ${analysis.turns}  winner: ${analysis.winner}  duration: ${analysis.durationMs / 1000}s")
    println("  gsId chain: ${if (analysis.gsidChain.valid) "valid" else "BROKEN"} (${analysis.gsidChain.length} GSMs)")
    println("  violations: ${analysis.invariantViolations.size}")
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
    val underRoot = File(NexusPaths.RECORDINGS, arg)
    if (underRoot.isDirectory) return underRoot

    // Try via RecordingInspector (base64 id)
    val resolved = RecordingInspector.resolveSessionDir(arg)
    if (resolved != null) return resolved

    System.err.println("Session not found: $arg")
    return null
}

private fun resolveLatest(): File? {
    val latest = File(NexusPaths.RECORDINGS, "latest")
    val target = if (latest.exists()) {
        latest.canonicalFile
    } else {
        // Fall back to most recent timestamped dir
        NexusPaths.RECORDINGS.listFiles()
            ?.filter { it.isDirectory && it.name != "latest" }
            ?.maxByOrNull { it.name }
    }
    if (target == null || !target.isDirectory) {
        System.err.println("No recording sessions found")
        return null
    }
    return target
}
