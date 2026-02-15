package forge.nexus.conformance

import java.io.File

/**
 * CLI: compare our ProtoDump output against a real recording, extract golden files,
 * or analyze a full-game JSON into a structured timeline.
 *
 * Usage:
 *   proto-compare <real-payload-dir>                          — diff real vs our output
 *   proto-compare --extract <dir> <action-name>               — extract golden from recording
 *   proto-compare --analyze <json-file> [--goldens <dir>]     — structured game timeline
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage:")
        println("  proto-compare <real-payload-dir>                          — diff real vs our output")
        println("  proto-compare --extract <dir> <action-name>               — extract golden from recording")
        println("  proto-compare --analyze <json-file> [--goldens <dir>]     — structured game timeline")
        return
    }

    if (args.firstOrNull() == "--analyze") {
        runAnalyze(args.drop(1))
        return
    }

    if (args.firstOrNull() == "--extract") {
        require(args.size >= 3) { "Usage: proto-compare --extract <dir> <action-name>" }
        val dir = File(args[1])
        val name = args[2]
        val fps = RecordingParser.parseDirectory(dir)
        println("Extracted ${fps.size} GRE fingerprints from ${dir.name}")
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

    val realFps = RecordingParser.parseDirectory(realDir)
    println("Real recording: ${realFps.size} GRE messages from ${realDir.name}")

    val ourDir = File("/tmp/arena-dump")
    if (!ourDir.isDirectory) {
        println("No /tmp/arena-dump/ found — run ARENA_DUMP=1 just serve first")
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
