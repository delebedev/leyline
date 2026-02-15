package forge.nexus.conformance

import java.io.File

/**
 * CLI: compare our ProtoDump output against a real recording, or extract golden files.
 *
 * Usage:
 *   proto-compare <real-payload-dir>              — diff real vs our output
 *   proto-compare --extract <dir> <action-name>   — extract golden from recording
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage:")
        println("  proto-compare <real-payload-dir>              — diff real vs our output")
        println("  proto-compare --extract <dir> <action-name>   — extract golden from recording")
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
