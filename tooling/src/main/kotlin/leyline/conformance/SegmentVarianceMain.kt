package leyline.conformance

/**
 * CLI: scan recordings, compute segment variance profiles.
 *
 * Usage: segment-variance [category] [--session X]
 *
 * No args: list all categories with instance counts.
 * With category: detailed variance profile for that category.
 */
fun main(args: Array<String>) {
    val category = args.firstOrNull { !it.startsWith("--") }
    val sessionFlag = ConformanceConstants.flagValue(args.toList(), "--session")

    // Detect all segments
    val allSegments =
        if (sessionFlag != null) {
            val frames = RecordingFrameLoader.load(sessionFlag, seat = 0)
            SegmentDetector.detect(frames, sessionFlag)
        } else {
            SegmentDetector.scanAll(seat = 0)
        }

    val grouped = SegmentDetector.groupByCategory(allSegments)

    if (category == null) {
        // Summary: list all categories
        println("Segments across ${allSegments.map { it.session }.toSet().size} session(s):")
        println()
        for ((cat, segs) in grouped.entries.sortedByDescending { it.value.size }) {
            val sessions = segs.map { it.session }.toSet().size
            val confidence = Confidence.from(segs.size, sessions)
            println("  %-30s %4d instances, %2d sessions  [%s]".format(cat, segs.size, sessions, confidence))
        }
        return
    }

    // Detailed profile for one category
    val segments = grouped[category]
    if (segments.isNullOrEmpty()) {
        println("No segments found for category: $category")
        println("Available: ${grouped.keys.sorted().joinToString(", ")}")
        return
    }

    val profile = FieldVarianceProfiler.profile(segments)
    printProfile(profile)
}

private fun printProfile(p: SegmentProfile) {
    println("${p.category} — ${p.instanceCount} instances, ${p.sessionCount} sessions [${p.confidence}]")
    println()

    // Annotation presence
    if (p.annotationPresence.isNotEmpty()) {
        val always = p.annotationPresence.filter { it.value.frequency >= 1.0 }
        val sometimes = p.annotationPresence.filter { it.value.frequency < 1.0 }

        if (always.isNotEmpty()) {
            println("ANNOTATIONS (always present):")
            for ((type, pres) in always.entries.sortedByDescending { it.value.instanceCount }) {
                val co = if (pres.coOccursWith.isNotEmpty()) " co-occurs: ${pres.coOccursWith}" else ""
                println("  %-35s %d/%d%s".format(type, pres.instanceCount, p.instanceCount, co))
            }
            println()
        }
        if (sometimes.isNotEmpty()) {
            println("ANNOTATIONS (sometimes):")
            for ((type, pres) in sometimes.entries.sortedByDescending { it.value.frequency }) {
                println(
                    "  %-35s %d/%d (%.0f%%)".format(
                        type,
                        pres.instanceCount,
                        p.instanceCount,
                        pres.frequency * 100,
                    ),
                )
            }
            println()
        }
    }

    // Field profiles — group by variance type
    println("FIELDS:")
    val constant = p.fieldProfiles.filter { it.value.variance == ValueVariance.CONSTANT }
    val enums = p.fieldProfiles.filter { it.value.variance == ValueVariance.ENUM }
    val ids = p.fieldProfiles.filter { it.value.variance == ValueVariance.ID }
    val other =
        p.fieldProfiles.filter {
            it.value.variance !in setOf(ValueVariance.CONSTANT, ValueVariance.ENUM, ValueVariance.ID)
        }

    if (constant.isNotEmpty()) {
        println("  Constant:")
        for ((path, fp) in constant.entries.sortedBy { it.key }) {
            val freq = if (fp.frequency < 1.0) " (%.0f%%)".format(fp.frequency * 100) else ""
            println("    %-45s = %s%s".format(path, fp.samples.firstOrNull() ?: "?", freq))
        }
    }
    if (enums.isNotEmpty()) {
        println("  Enum:")
        for ((path, fp) in enums.entries.sortedBy { it.key }) {
            println("    %-45s values: %s".format(path, fp.samples))
        }
    }
    if (ids.isNotEmpty()) {
        println("  ID (instance-specific):")
        for ((path, _) in ids.entries.sortedBy { it.key }) {
            println("    %-45s (varies)".format(path))
        }
    }
    if (other.isNotEmpty()) {
        println("  Other:")
        for ((path, fp) in other.entries.sortedBy { it.key }) {
            println("    %-45s %s samples: %s".format(path, fp.variance, fp.samples))
        }
    }
}
