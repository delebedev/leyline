package forge.nexus.conformance

import forge.nexus.debug.NexusPaths
import java.io.File

/**
 * CLI: compare our ProtoDump output against a real recording, extract golden files,
 * or analyze a full-game JSON into a structured timeline.
 *
 * Usage:
 *   proto-compare <real-payload-dir>                          — diff real vs our output
 *   proto-compare --extract <dir> <action-name> [--seat N]    — extract golden from recording
 *   proto-compare --analyze <json-file> [--goldens <dir>]     — structured game timeline
 *
 * Seat filtering (--extract):
 *   --seat 1   Extract player perspective only (default)
 *   --seat 2   Extract AI/Sparky perspective
 *   --seat 0   Extract all seats (interleaved, legacy behavior)
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage:")
        println("  proto-compare <real-payload-dir>                          — diff real vs our output")
        println("  proto-compare --extract <dir> <action-name> [--seat N]    — extract golden from recording")
        println("  proto-compare --analyze <json-file> [--goldens <dir>]     — structured game timeline")
        println()
        println("Seat filtering (--extract):")
        println("  --seat 1   Player perspective (default)")
        println("  --seat 2   AI/Sparky perspective")
        println("  --seat 0   All seats interleaved (legacy)")
        return
    }

    if (args.firstOrNull() == "--analyze") {
        runAnalyze(args.drop(1))
        return
    }

    if (args.firstOrNull() == "--extract") {
        require(args.size >= 3) { "Usage: proto-compare --extract <dir> <action-name> [--seat N]" }
        val dir = File(args[1])
        val name = args[2]

        // Parse --seat flag (default: 1 = player perspective)
        val seatFilter = parseSeatFilter(args.toList())

        // Detect and print seat identities
        val seats = RecordingDecoder.detectSeats(dir)
        printSeatIdentification(seats)

        val fps = RecordingParser.parseDirectory(dir, seatFilter)
        val filterDesc = if (seatFilter != null) " (seat $seatFilter)" else " (all seats)"
        println("Extracted ${fps.size} GRE fingerprints from ${dir.name}$filterDesc")
        for ((i, fp) in fps.withIndex()) {
            println(
                "  [$i] ${fp.greMessageType} gsType=${fp.gsType} update=${fp.updateType} " +
                    "annotations=${fp.annotationTypes} actions=${fp.actionTypes}",
            )
        }
        val outFile = File("src/test/resources/golden/$name.json")
        outFile.parentFile.mkdirs()
        outFile.writeText(GoldenSequence.toJson(fps))
        println("\nSaved golden -> ${outFile.path}")
        return
    }

    val realDir = File(args[0])
    if (!realDir.isDirectory) {
        println("Error: ${realDir.path} is not a directory")
        return
    }

    // Detect seats for the diff path too
    val seats = RecordingDecoder.detectSeats(realDir)
    if (seats.isNotEmpty()) {
        println("Seat identification:")
        for ((id, info) in seats.toSortedMap()) {
            val role = if (info.isBot) "AI" else "player"
            println("  Seat $id: ${info.playerName} ($role)")
        }
        println()
    }

    // Default: seat 1 (player perspective)
    val seatFilter = 1
    val realFps = RecordingParser.parseDirectory(realDir, seatFilter)
    println("Real recording: ${realFps.size} GRE messages from ${realDir.name} (seat $seatFilter)")

    val ourDir = NexusPaths.ENGINE_DUMP
    if (!ourDir.isDirectory) {
        println("No ${NexusPaths.ENGINE_DUMP}/ found — run just serve first (recording is always-on)")
        println("\nReal recording fingerprints:")
        for ((i, fp) in realFps.withIndex()) {
            println(
                "  [$i] ${fp.greMessageType} gsType=${fp.gsType} update=${fp.updateType} " +
                    "annotations=${fp.annotationTypes}",
            )
        }
        return
    }

    val ourFps = RecordingParser.parseDirectory(ourDir)
    println("Our output: ${ourFps.size} GRE messages from ${ourDir.name}")

    if (realFps.isEmpty() || ourFps.isEmpty()) {
        println("Nothing to compare")
        return
    }

    val result = StructuralDiff.compare(realFps, ourFps)
    println("\n${result.report()}")
}

private fun runAnalyze(args: List<String>) {
    if (args.isEmpty()) {
        println("Usage: proto-compare --analyze <json-file> [--goldens <dir>]")
        return
    }

    val jsonFile = File(args[0])
    if (!jsonFile.exists()) {
        println("Error: ${jsonFile.path} does not exist")
        return
    }

    // Parse --goldens arg, default to golden dir relative to json file
    val goldensDir = if (args.size >= 3 && args[1] == "--goldens") {
        File(args[2])
    } else {
        // Default: same directory as the JSON file (golden/ is typically at that level)
        jsonFile.parentFile
    }

    val fps = GoldenSequence.fromFile(jsonFile)
    println("Loaded ${fps.size} fingerprints from ${jsonFile.name}")
    println("Goldens dir: ${goldensDir.absolutePath}")
    println()

    val analyzer = GameFlowAnalyzer(fps, goldensDir)
    print(analyzer.report())
}
