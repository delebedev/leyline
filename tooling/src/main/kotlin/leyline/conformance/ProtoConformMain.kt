package leyline.conformance

import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import java.io.File

/**
 * CLI entry point for `just conform-proto`.
 *
 * Decode mode (default): load recording frames, print full proto text for inspection.
 * Diff mode (--engine <dir>): load recording + engine frames, diff via ProtoDiffer.
 *
 * Usage: conform-proto <greType> <session> [--gsid N] [--index N] [--engine <dir>] [--seat N]
 *
 * Examples:
 *   conform-proto SearchReq 2026-03-21_22-05-00
 *   conform-proto GameStateMessage 2026-03-21_22-05-00 --gsid 52
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: conform-proto <greType> <session> [--gsid N] [--index N] [--engine <dir>] [--seat N]")
        println()
        println("Examples:")
        println("  conform-proto SearchReq 2026-03-21_22-05-00")
        println("  conform-proto GameStateMessage 2026-03-21_22-05-00 --gsid 52")
        println("  conform-proto SearchReq 2026-03-21_22-05-00 --engine recordings/2026-03-21_22-05-00/engine")
        return
    }

    val greTypeName = args[0]
    val session = args[1]
    val argList = args.drop(2)

    val gsId = flagInt(argList, "--gsid")
    val index = flagInt(argList, "--index")
    val engineDir = flagValue(argList, "--engine")?.let { File(it) }
    val seat = flagInt(argList, "--seat") ?: 1
    val useProfile = "--profile" in argList

    // Resolve GREMessageType by prefix match
    val greType = GREMessageType.values().firstOrNull { it.name.startsWith(greTypeName, ignoreCase = true) }
    if (greType == null) {
        System.err.println("Unknown GREMessageType prefix: $greTypeName")
        System.err.println("Known types (sample): ${GREMessageType.values().take(10).joinToString { it.name }}")
        System.exit(1)
        return
    }

    // Load recording frames
    val allFrames = when {
        gsId != null -> RecordingFrameLoader.loadByGsId(session, gsId, seat)
        else -> RecordingFrameLoader.loadByType(session, greType, seat)
    }.let { frames ->
        if (index != null) frames.filter { it.frameIndex == index } else frames
    }

    println("Session: $session  type: ${greType.name}  seat: $seat")
    if (gsId != null) println("gsid filter: $gsId")
    if (index != null) println("index filter: $index")
    println("Frames loaded: ${allFrames.size}")
    println()

    if (allFrames.isEmpty()) {
        println("No frames matched.")
        return
    }

    if (engineDir == null) {
        // Decode mode: print full proto text
        for (frame in allFrames) {
            println("=== Frame ${frame.frameIndex} ===")
            println(frame.message.toString())
            println()
        }
        // Profile mode without engine: show relationship results against recording frames
        if (useProfile) {
            val detectedSegments = SegmentDetector.detect(allFrames, session)
            val category = detectedSegments.firstOrNull()?.category
            if (category != null) {
                val allSegments = SegmentDetector.scanAll(seat = seat)
                val categorySegments = allSegments.filter { it.category == category }
                val profile = FieldVarianceProfiler.profile(categorySegments)
                println("Profile: $category — ${profile.instanceCount} instances [${profile.confidence}]")
                println()
                val patterns = RelationshipCatalog.forCategory(category)
                if (patterns.isNotEmpty()) {
                    println("Relationship results (against recording frames):")
                    val results = RelationshipValidator.validateAll(patterns, detectedSegments)
                    for (r in results) {
                        val symbol = when (r.status) {
                            ValidationStatus.HOLDS -> "✓"
                            ValidationStatus.MOSTLY_HOLDS -> "?"
                            ValidationStatus.VIOLATED -> "✗"
                            ValidationStatus.NO_DATA -> "-"
                            ValidationStatus.UNRESOLVABLE -> "!"
                        }
                        println("  $symbol ${r.pattern}  ${r.holds}/${r.total}")
                        for (ex in r.exceptions.take(3)) println("      ${ex.detail}")
                    }
                }
            }
        }
        return
    }

    // Diff mode: load engine frames, diff matching frames
    if (!engineDir.isDirectory) {
        System.err.println("Engine dir not found: ${engineDir.path}")
        System.exit(1)
        return
    }

    val engineFrames = RecordingFrameLoader.loadFromDir(engineDir, seatFilter = null)
        .filter { it.message.type == greType }
        .let { frames ->
            if (gsId != null) frames.filter { it.message.gameStateId == gsId } else frames
        }

    println("Engine frames: ${engineFrames.size}")
    println()

    if (engineFrames.isEmpty()) {
        println("No engine frames matched. Showing recording frames only:")
        for (frame in allFrames) {
            println("=== Frame ${frame.frameIndex} ===")
            println(frame.message.toString())
        }
        return
    }

    // Compute profile if requested
    val profile: SegmentProfile? = if (useProfile) {
        val firstFrame = allFrames.firstOrNull()
        if (firstFrame != null) {
            val detectedSegments = SegmentDetector.detect(listOf(firstFrame), session)
            val category = detectedSegments.firstOrNull()?.category
            if (category != null) {
                println("Profile mode: category=$category")
                val allSegments = SegmentDetector.scanAll(seat = seat)
                val categorySegments = allSegments.filter { it.category == category }
                println("Profile instances: ${categorySegments.size} across ${categorySegments.map { it.session }.toSet().size} sessions")
                println()
                FieldVarianceProfiler.profile(categorySegments)
            } else {
                println("Profile mode: could not detect category from recording frame (no ZoneTransfer or standalone type)")
                null
            }
        } else {
            null
        }
    } else {
        null
    }

    // Pair frames by position and diff
    val maxLen = maxOf(allFrames.size, engineFrames.size)
    for (i in 0 until maxLen) {
        val rec = allFrames.getOrNull(i)
        val eng = engineFrames.getOrNull(i)
        println("=== Pair $i ===")
        when {
            rec == null -> println("  Recording: (none)  Engine frame ${eng!!.frameIndex}: present")
            eng == null -> println("  Recording frame ${rec.frameIndex}: present  Engine: (none)")
            else -> {
                println("  Recording frame ${rec.frameIndex}  Engine frame ${eng.frameIndex}")
                val result = ProtoDiffer.diff(rec.message, eng.message, profile)
                println(result.report(verbose = true))
            }
        }
        println()
    }
}

private fun flagValue(args: List<String>, flag: String): String? {
    val idx = args.indexOf(flag)
    return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
}

private fun flagInt(args: List<String>, flag: String): Int? = flagValue(args, flag)?.toIntOrNull()
