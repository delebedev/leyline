package leyline.debug

import leyline.game.AnnotationBuilder
import leyline.recording.RecordingDecoder
import leyline.recording.RecordingDecoder.AnnotationSummary
import leyline.recording.RecordingDecoder.DecodedMessage
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import java.io.File

/**
 * CLI tool: annotation detail-key variance across all proxy recordings.
 *
 * Scans proxy capture dirs, extracts every annotation instance grouped by type,
 * builds a variance profile (always/sometimes keys, value samples), compares
 * against our AnnotationBuilder output, and links each finding to concrete
 * game moments for scenario reconstruction.
 *
 * Usage: proto-annotation-variance [recordings-dir] [--examples N] [--type TypeName] [--skip-ok] [--summary]
 */
fun main(args: Array<String>) {
    val maxExamples = flagInt(args, "--examples") ?: 2
    val typeFilter = flagValue(args, "--type")
    val skipOk = "--skip-ok" in args
    val summaryOnly = "--summary" in args
    val recordingsDir = args.firstOrNull { !it.startsWith("--") && flagValue(args, "--examples") != it && flagValue(args, "--type") != it }
        ?.let { File(it) }
        ?: File("recordings")

    if (!recordingsDir.isDirectory) {
        System.err.println("Recordings dir not found: $recordingsDir")
        System.exit(1)
        return
    }

    // Discover proxy capture directories (both old flat layout and new seat-specific layout)
    val captureDirs = recordingsDir.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { sessionDir ->
            val payloads = File(sessionDir, "capture/payloads")
            if (payloads.isDirectory) return@mapNotNull sessionDir to payloads
            val seatPayloads = File(sessionDir, "capture/seat-1/md-payloads")
            if (seatPayloads.isDirectory) return@mapNotNull sessionDir to seatPayloads
            null
        }
        ?.sortedBy { it.first.name }
        ?: emptyList()

    if (captureDirs.isEmpty()) {
        System.err.println("No proxy captures found under $recordingsDir")
        System.exit(1)
        return
    }

    // Collect annotation instances across all sessions
    val collector = VarianceCollector()
    var totalPayloads = 0
    var totalGsms = 0

    for ((sessionDir, payloadsDir) in captureDirs) {
        val sessionName = sessionDir.name.removePrefix("2026-02-28_") // short label
        val messages = RecordingDecoder.decodeDirectory(payloadsDir)
        totalPayloads += RecordingDecoder.listRecordingFiles(payloadsDir).size

        // #3: Build per-gsId instanceId→card snapshots (not global last-seen)
        val instanceToCard = mutableMapOf<Int, String>()
        val cardSnapshots = mutableMapOf<Int, Map<Int, String>>() // gsId → snapshot

        // #4: Track seen (gsId, typeList) pairs for per-seat dedup
        val seenAnnotations = mutableSetOf<String>()

        for (msg in messages) {
            // Update running map with objects from this message
            for (obj in msg.objects) {
                if (obj.grpId != 0) {
                    instanceToCard[obj.instanceId] = "grp:${obj.grpId}"
                }
            }
            // Snapshot at each gsId
            if (msg.gsId > 0) {
                cardSnapshots[msg.gsId] = instanceToCard.toMap()
            }
        }

        for (msg in messages) {
            if (msg.gsId > 0) totalGsms++
            val snapshot = cardSnapshots[msg.gsId] ?: instanceToCard
            collectAnnotations(collector, msg, msg.annotations, sessionName, snapshot, seenAnnotations)
            collectAnnotations(collector, msg, msg.persistentAnnotations, sessionName, snapshot, seenAnnotations)
        }
    }

    // Print report
    val types = collector.types()
    val filtered = if (typeFilter != null) {
        types.filter { it.name.equals(typeFilter, ignoreCase = true) }
    } else {
        types
    }

    // Sort: MISMATCH first, then NOT_IMPLEMENTED, then OK. Within each group, by instance count desc.
    val statusOrder = mapOf(Status.MISMATCH to 0, Status.NOT_IMPLEMENTED to 1, Status.OK to 2)
    val sorted = filtered.sortedWith(
        compareBy<TypeProfile> { statusOrder[compareStatus(it).first] ?: 3 }
            .thenByDescending { it.instanceCount },
    )

    val counts = sorted.groupBy { compareStatus(it).first }
    val okCount = counts[Status.OK]?.size ?: 0
    val mismatchCount = counts[Status.MISMATCH]?.size ?: 0
    val notImplCount = counts[Status.NOT_IMPLEMENTED]?.size ?: 0

    println("# Annotation Variance Report")
    println(
        "${captureDirs.size} sessions, $totalPayloads S-C payloads, " +
            "$totalGsms GSMs, ${collector.totalInstances()} annotation instances (deduped per-seat), " +
            "${types.size} distinct types",
    )
    println("Status: $mismatchCount MISMATCH, $notImplCount NOT IMPLEMENTED, $okCount OK")
    println()

    if (summaryOnly) {
        printSummaryTable(sorted)
        return
    }

    val displayed = if (skipOk) sorted.filter { compareStatus(it).first != Status.OK } else sorted
    if (skipOk && okCount > 0) {
        println("_($okCount OK types hidden — use without `--skip-ok` to show all)_")
        println()
    }

    for (profile in displayed) {
        printTypeProfile(profile, maxExamples)
    }
}

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

private data class AnnotationInstance(
    val session: String,
    val gsId: Int,
    val msgId: Int,
    val turn: Int?,
    val phase: String?,
    val file: String,
    val affectedIds: List<Int>,
    val affectedCards: List<String>, // resolved card names
    val affectorId: Int, // #5: source of effect
    val affectorCard: String?, // #5: resolved card name for affector
    val coTypes: List<String>, // #1: full type list from the original annotation
    val detailKeys: Set<String>,
    val detailValues: Map<String, String>, // key → sample value as string
)

private data class TypeProfile(
    val name: String,
    val instanceCount: Int,
    val sessionCount: Int,
    val alwaysKeys: Set<String>,
    val sometimesKeys: Map<String, Int>, // key → count (< instanceCount)
    val valueSamples: Map<String, List<String>>, // key → up to 5 unique values
    val instances: List<AnnotationInstance>,
)

// ---------------------------------------------------------------------------
// Collection
// ---------------------------------------------------------------------------

private class VarianceCollector {
    // type name → list of instances
    private val byType = mutableMapOf<String, MutableList<AnnotationInstance>>()

    fun add(typeName: String, instance: AnnotationInstance) {
        byType.getOrPut(typeName) { mutableListOf() }.add(instance)
    }

    fun totalInstances(): Int = byType.values.sumOf { it.size }

    fun types(): List<TypeProfile> = byType.map { (name, instances) ->
        val sessions = instances.map { it.session }.toSet()

        // Key frequency
        val keyCounts = mutableMapOf<String, Int>()
        val valueSamples = mutableMapOf<String, MutableSet<String>>()
        for (inst in instances) {
            for (key in inst.detailKeys) {
                keyCounts[key] = (keyCounts[key] ?: 0) + 1
                val samples = valueSamples.getOrPut(key) { mutableSetOf() }
                inst.detailValues[key]?.let { if (samples.size < 5) samples.add(it) }
            }
        }

        val always = keyCounts.filter { it.value == instances.size }.keys
        val sometimes = keyCounts.filter { it.value < instances.size }

        TypeProfile(
            name = name,
            instanceCount = instances.size,
            sessionCount = sessions.size,
            alwaysKeys = always,
            sometimesKeys = sometimes,
            valueSamples = valueSamples.mapValues { it.value.toList() },
            instances = instances,
        )
    }
}

private val PROTO_SUFFIX = Regex("_[a-f0-9]{3,4}$")

private fun collectAnnotations(
    collector: VarianceCollector,
    msg: DecodedMessage,
    annotations: List<AnnotationSummary>,
    sessionName: String,
    cardSnapshot: Map<Int, String>,
    seenAnnotations: MutableSet<String>,
) {
    for (ann in annotations) {
        val detailKeys = ann.details.keys.toSet()
        val detailValues = ann.details.mapValues { (_, v) -> formatValue(v) }
        val affectedCards = ann.affectedIds.mapNotNull { cardSnapshot[it] } // #3: per-gsId
        val affectorCard = if (ann.affectorId != 0) cardSnapshot[ann.affectorId] else null // #5
        val coTypes = ann.types.map { it.replace(PROTO_SUFFIX, "") } // #1: full type list

        for (rawType in ann.types) {
            val typeName = rawType.replace(PROTO_SUFFIX, "")

            // #4: Per-seat dedup — skip if same (session, gsId, annotationId, type) seen
            val dedupKey = "$sessionName:${msg.gsId}:${ann.id}:$typeName"
            if (!seenAnnotations.add(dedupKey)) continue

            collector.add(
                typeName,
                AnnotationInstance(
                    session = sessionName,
                    gsId = msg.gsId,
                    msgId = msg.msgId,
                    turn = msg.turnInfo?.turn,
                    phase = msg.turnInfo?.phase,
                    file = msg.file,
                    affectedIds = ann.affectedIds,
                    affectedCards = affectedCards,
                    affectorId = ann.affectorId, // #5
                    affectorCard = affectorCard, // #5
                    coTypes = coTypes, // #1
                    detailKeys = detailKeys,
                    detailValues = detailValues,
                ),
            )
        }
    }
}

private fun formatValue(v: Any?): String = when (v) {
    is List<*> -> v.joinToString(",")
    null -> "null"
    else -> v.toString()
}

// ---------------------------------------------------------------------------
// Builder comparison
// ---------------------------------------------------------------------------

private fun keysOf(ann: AnnotationInfo): Set<String> =
    ann.detailsList.map { it.key }.toSet()

/** Our builders mapped by annotation type name → detail keys they produce. */
private val OUR_BUILDERS: Map<String, Set<String>> by lazy {
    mapOf(
        "ZoneTransfer" to keysOf(AnnotationBuilder.zoneTransfer(1, 31, 28, "X")),
        "ResolutionStart" to keysOf(AnnotationBuilder.resolutionStart(1, 1)),
        "ResolutionComplete" to keysOf(AnnotationBuilder.resolutionComplete(1, 1)),
        "ObjectIdChanged" to keysOf(AnnotationBuilder.objectIdChanged(1, 2)),
        "UserActionTaken" to keysOf(AnnotationBuilder.userActionTaken(1, 1, 1, 0)),
        "ManaPaid" to keysOf(AnnotationBuilder.manaPaid(spellInstanceId = 1, landInstanceId = 2, manaId = 1, color = 4)),
        "TappedUntappedPermanent" to keysOf(AnnotationBuilder.tappedUntappedPermanent(1, 2)),
        "AbilityInstanceCreated" to keysOf(AnnotationBuilder.abilityInstanceCreated(abilityInstanceId = 1, sourceZoneId = 31)),
        "AbilityInstanceDeleted" to keysOf(AnnotationBuilder.abilityInstanceDeleted(abilityInstanceId = 1)),
        "DamageDealt" to keysOf(AnnotationBuilder.damageDealt(1, 2, 3)),
        "ModifiedLife" to keysOf(AnnotationBuilder.modifiedLife(1, -3)),
        "ModifiedPower" to keysOf(AnnotationBuilder.modifiedPower(1)),
        "ModifiedToughness" to keysOf(AnnotationBuilder.modifiedToughness(1)),
        "PhaseOrStepModified" to keysOf(AnnotationBuilder.phaseOrStepModified(1, 1, 2)),
        "NewTurnStarted" to keysOf(AnnotationBuilder.newTurnStarted(1)),
        "SyntheticEvent" to keysOf(AnnotationBuilder.syntheticEvent(1, 1)),
        "EnteredZoneThisTurn" to keysOf(AnnotationBuilder.enteredZoneThisTurn(28, 1)),
        "LossOfGame" to keysOf(AnnotationBuilder.lossOfGame(1, 0)),
        "TokenCreated" to keysOf(AnnotationBuilder.tokenCreated(1)),
        "TokenDeleted" to keysOf(AnnotationBuilder.tokenDeleted(1)),
        "CounterAdded" to keysOf(AnnotationBuilder.counterAdded(1, "P1P1", 2)),
        "CounterRemoved" to keysOf(AnnotationBuilder.counterRemoved(1, "X", 1)),
        "Shuffle" to keysOf(AnnotationBuilder.shuffle(1)),
        "Scry" to keysOf(AnnotationBuilder.scry(1, 2, 1)),
        "AttachmentCreated" to keysOf(AnnotationBuilder.attachmentCreated(1, 2)),
        "Attachment" to keysOf(AnnotationBuilder.attachment(1, 2)),
        "RemoveAttachment" to keysOf(AnnotationBuilder.removeAttachment(1)),
        "RevealedCardCreated" to keysOf(AnnotationBuilder.revealedCardCreated(1)),
        "RevealedCardDeleted" to keysOf(AnnotationBuilder.revealedCardDeleted(1)),
        // Tier 1 state annotations
        "Counter" to keysOf(AnnotationBuilder.counter(1, 1, 1)),
        "AddAbility" to keysOf(AnnotationBuilder.addAbility(1, 1, 1, 1, 1)),
        "RemoveAbility" to keysOf(AnnotationBuilder.removeAbility(1, 1)),
        "AbilityExhausted" to keysOf(AnnotationBuilder.abilityExhausted(1, 1, 0, 1)),
        "GainDesignation" to keysOf(AnnotationBuilder.gainDesignation(1, 19)),
        "Designation" to keysOf(AnnotationBuilder.designation(1, 19)),
        "LayeredEffect" to keysOf(AnnotationBuilder.layeredEffect(1, 7000, powerDelta = 1, toughnessDelta = 1)),
        // Tier 2 detail-carrying annotations
        "ColorProduction" to keysOf(AnnotationBuilder.colorProduction(1, listOf(4))),
        "TriggeringObject" to keysOf(AnnotationBuilder.triggeringObject(1, 27)),
        "TargetSpec" to keysOf(AnnotationBuilder.targetSpec(1, 1, 1, 1, 1)),
        "PowerToughnessModCreated" to keysOf(AnnotationBuilder.powerToughnessModCreated(1, 1, 1)),
        "DisplayCardUnderCard" to keysOf(AnnotationBuilder.displayCardUnderCard(1)),
        "PredictedDirectDamage" to keysOf(AnnotationBuilder.predictedDirectDamage(1, 2)),
        // Tier 2 detail-less annotations
        "LayeredEffectDestroyed" to keysOf(AnnotationBuilder.layeredEffectDestroyed(1)),
        "PlayerSelectingTargets" to keysOf(AnnotationBuilder.playerSelectingTargets(1)),
        "PlayerSubmittedTargets" to keysOf(AnnotationBuilder.playerSubmittedTargets(1)),
        "DamagedThisTurn" to keysOf(AnnotationBuilder.damagedThisTurn(1)),
        "InstanceRevealedToOpponent" to keysOf(AnnotationBuilder.instanceRevealedToOpponent(1)),
    )
}

private enum class Status { OK, MISMATCH, NOT_IMPLEMENTED }

private fun compareStatus(profile: TypeProfile): Triple<Status, Set<String>, Set<String>> {
    val ourKeys = OUR_BUILDERS[profile.name]
        ?: return Triple(Status.NOT_IMPLEMENTED, emptySet(), emptySet())
    val missing = profile.alwaysKeys - ourKeys
    val extra = ourKeys - profile.alwaysKeys - profile.sometimesKeys.keys
    return if (missing.isEmpty() && extra.isEmpty()) {
        Triple(Status.OK, emptySet(), emptySet())
    } else {
        Triple(Status.MISMATCH, missing, extra)
    }
}

// ---------------------------------------------------------------------------
// Output
// ---------------------------------------------------------------------------

private fun printSummaryTable(profiles: List<TypeProfile>) {
    println("| Type | Count | Sessions | Co-types | Server Keys | Our Keys | Status |")
    println("|---|---|---|---|---|---|---|")
    for (p in profiles) {
        val (status, missing, extra) = compareStatus(p)
        val serverKeys = (p.alwaysKeys.sorted() + p.sometimesKeys.keys.sorted().map { "$it?" }).joinToString(", ").ifEmpty { "-" }
        val ourKeys = OUR_BUILDERS[p.name]?.sorted()?.joinToString(", ") ?: "-"
        val statusLabel = when (status) {
            Status.OK -> "OK"
            Status.MISMATCH -> "MISMATCH"
            Status.NOT_IMPLEMENTED -> "NOT IMPL"
        }
        val detail = buildString {
            if (missing.isNotEmpty()) append(" miss={${missing.sorted().joinToString(",")}}")
            if (extra.isNotEmpty()) append(" extra={${extra.sorted().joinToString(",")}}")
        }
        // #1: Show co-type bundles in summary
        val coTypeBundles = p.instances.map { it.coTypes.sorted() }.toSet()
        val coTypeLabel = coTypeBundles
            .filter { it.size > 1 }
            .map { bundle -> bundle.filter { it != p.name }.joinToString("+") }
            .filter { it.isNotEmpty() }
            .joinToString("; ")
            .ifEmpty { "-" }
        println("| ${p.name} | ${p.instanceCount} | ${p.sessionCount} | $coTypeLabel | $serverKeys | $ourKeys | $statusLabel$detail |")
    }
    println()
}

private fun printTypeProfile(profile: TypeProfile, maxExamples: Int) {
    val (status, missing, extra) = compareStatus(profile)
    val statusLabel = when (status) {
        Status.OK -> "OK"
        Status.MISMATCH -> "MISMATCH"
        Status.NOT_IMPLEMENTED -> "NOT IMPLEMENTED"
    }

    println(
        "## ${profile.name} (${profile.instanceCount} instances, " +
            "${profile.sessionCount} sessions)  -- $statusLabel",
    )

    // Always-present keys
    if (profile.alwaysKeys.isNotEmpty()) {
        println("  Always:    {${profile.alwaysKeys.sorted().joinToString(", ")}}")
    } else {
        println("  Always:    (no detail keys)")
    }

    // Sometimes-present keys with frequency
    if (profile.sometimesKeys.isNotEmpty()) {
        val parts = profile.sometimesKeys.entries
            .sortedByDescending { it.value }
            .joinToString(", ") { (k, v) ->
                val pct = (v * 100) / profile.instanceCount
                "$k ($pct%)"
            }
        println("  Sometimes: $parts")
    }

    // Our builder comparison
    val ourKeys = OUR_BUILDERS[profile.name]
    if (ourKeys != null) {
        if (ourKeys.isNotEmpty()) {
            println("  Our keys:  {${ourKeys.sorted().joinToString(", ")}}")
        } else {
            println("  Our keys:  (no detail keys)")
        }
    } else {
        println("  Our keys:  --")
    }

    if (missing.isNotEmpty()) println("  Missing:   {${missing.sorted().joinToString(", ")}}")
    if (extra.isNotEmpty()) println("  Extra:     {${extra.sorted().joinToString(", ")}}")

    // Value samples
    if (profile.valueSamples.isNotEmpty()) {
        val sampleParts = profile.valueSamples.entries
            .sortedBy { it.key }
            .joinToString(", ") { (k, v) -> "$k=${v.take(3)}" }
        println("  Samples:   $sampleParts")
    }

    // #1: Co-type detection — show if this type always appears with others
    val coTypeBundles = profile.instances.map { it.coTypes.sorted() }.toSet()
    if (coTypeBundles.any { it.size > 1 }) {
        val bundleLabels = coTypeBundles
            .filter { it.size > 1 }
            .map { "[${it.joinToString(", ")}]" }
        println("  Co-types:  ${bundleLabels.joinToString(" | ")}")
    }

    // Examples with provenance
    println()
    val examples = profile.instances.take(maxExamples)
    for ((i, inst) in examples.withIndex()) {
        val turnPhase = buildString {
            inst.turn?.let { append("T$it") }
            inst.phase?.let {
                if (isNotEmpty()) append(" ")
                append(it)
            }
        }.ifEmpty { "?" }

        val cardLabels = inst.affectedIds.mapIndexed { idx, id ->
            val card = inst.affectedCards.getOrNull(idx)
            if (card != null) "$id → $card" else "$id"
        }.joinToString(", ")

        // #5: affectorId with resolved card name
        val affectorLabel = if (inst.affectorId != 0) {
            val card = inst.affectorCard
            if (card != null) "${inst.affectorId} → $card" else "${inst.affectorId}"
        } else {
            ""
        }

        println(
            "  [${i + 1}] session=${inst.session} gsId=${inst.gsId} " +
                "msg=${inst.msgId} $turnPhase",
        )
        if (affectorLabel.isNotEmpty()) {
            println("      affectorId=$affectorLabel")
        }
        println("      affectedIds=[$cardLabels] details=${inst.detailValues}")
        println("      file: ${inst.file}")
    }
    println()
}

// ---------------------------------------------------------------------------
// CLI helpers
// ---------------------------------------------------------------------------

private fun flagValue(args: Array<String>, flag: String): String? {
    val idx = args.indexOf(flag)
    if (idx < 0 || idx + 1 >= args.size) return null
    return args[idx + 1]
}

private fun flagInt(args: Array<String>, flag: String): Int? =
    flagValue(args, flag)?.toIntOrNull()
