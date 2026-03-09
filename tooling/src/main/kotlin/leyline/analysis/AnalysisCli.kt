package leyline.analysis

import leyline.LeylinePaths
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
                "annotation-contract <session> <type> [effectId] | violations [session] | mechanics | latest",
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
                    "${analysis.invariantViolations.size} violations, ${analysis.cardIndex.size} cards",
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
        val matches = analysis.cardIndex.filter {
            it.name.contains(keyword, ignoreCase = true)
        }
        if (matches.isNotEmpty()) {
            hits++
            val mode = analysis.mode.padEnd(6)
            println("${sessionDir.name}  $mode  T${analysis.turns}  ${matches.joinToString(", ") { "${it.name} (${it.grpId})" }}")
        }
    }
    if (hits == 0) {
        println("No sessions with cards matching '$keyword'. Run 'just rec-analyze-all --force' to rebuild card indexes.")
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
