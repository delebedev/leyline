package leyline.conformance

/**
 * CLI: validate relationship patterns against recording segments.
 *
 * Usage: segment-relationships [category]
 *
 * No args: validate all patterns across all categories.
 * With category: validate patterns for that category only.
 */
fun main(args: Array<String>) {
    val category = args.firstOrNull { !it.startsWith("--") && flagValue(args.toList(), "--engine") != it }
    val engineDir = flagValue(args.toList(), "--engine")

    // Load segments from engine dir or from recordings
    val allSegments = if (engineDir != null) {
        val dir = java.io.File(engineDir)
        if (!dir.isDirectory) {
            System.err.println("Engine dir not found: $engineDir")
            return
        }
        val frames = RecordingFrameLoader.loadFromDir(dir, seatFilter = null)
        println("Engine frames: ${frames.size} from $engineDir")
        SegmentDetector.detect(frames, "engine")
    } else {
        SegmentDetector.scanAll(seat = 0)
    }

    val patterns =
        if (category != null) {
            RelationshipCatalog.forCategory(category)
        } else {
            RelationshipCatalog.patterns
        }

    if (patterns.isEmpty()) {
        println("No patterns defined${if (category != null) " for $category" else ""}.")
        println("Available categories: ${RelationshipCatalog.patterns.map { it.category }.toSet().sorted()}")
        return
    }

    val results = RelationshipValidator.validateAll(patterns, allSegments)

    // Group by status for display
    val holds = results.filter { it.status == ValidationStatus.HOLDS }
    val mostly = results.filter { it.status == ValidationStatus.MOSTLY_HOLDS }
    val violated = results.filter { it.status == ValidationStatus.VIOLATED }
    val noData = results.filter { it.status == ValidationStatus.NO_DATA }

    val sessions = allSegments.map { it.session }.toSet().size
    println(
        "Relationship validation — ${results.size} patterns, ${allSegments.size} segments, $sessions sessions",
    )
    println()

    if (holds.isNotEmpty()) {
        println("HOLDS (${holds.size}):")
        for (r in holds) {
            println("  ✓ %-50s %d/%d".format(patternLabel(r.pattern), r.holds, r.total))
        }
        println()
    }

    if (mostly.isNotEmpty()) {
        println("MOSTLY HOLDS (${mostly.size}):")
        for (r in mostly) {
            println(
                "  ? %-50s %d/%d (%.0f%%)".format(
                    patternLabel(r.pattern),
                    r.holds,
                    r.total,
                    r.confidence * 100,
                ),
            )
            for (ex in r.exceptions.take(3)) {
                println("      exception: ${ex.session} gsId=${ex.gsId} — ${ex.detail}")
            }
        }
        println()
    }

    if (violated.isNotEmpty()) {
        println("VIOLATED (${violated.size}):")
        for (r in violated) {
            println("  ✗ %-50s %d/%d".format(patternLabel(r.pattern), r.holds, r.total))
            for (ex in r.exceptions.take(5)) {
                println("      ${ex.session} gsId=${ex.gsId} — ${ex.detail}")
            }
        }
        println()
    }

    if (noData.isNotEmpty()) {
        println("NO DATA (${noData.size}):")
        for (r in noData) {
            println("  - ${patternLabel(r.pattern)}")
        }
    }
}

private fun flagValue(args: List<String>, flag: String): String? {
    val idx = args.indexOf(flag)
    return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
}

private fun patternLabel(r: Relationship): String =
    when (r) {
        is Relationship.AlwaysPresent -> "[${r.category}] AlwaysPresent: ${r.annotationType}"
        is Relationship.NonEmpty -> "[${r.category}] NonEmpty: ${r.path}"
        is Relationship.ValueIn -> "[${r.category}] ValueIn: ${r.path} ∈ ${r.values}"
        is Relationship.Equals -> "[${r.category}] Equals: ${r.a} == ${r.b}"
    }
