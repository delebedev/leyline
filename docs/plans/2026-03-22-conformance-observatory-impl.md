# Conformance Observatory — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build observation-derived protocol conformance tools: segment variance profiling, relationship validation, and profile-aware verification.

**Architecture:** Three pieces on top of the existing `RecordingFrameLoader` + `ProtoDiffer` foundation. `SegmentVarianceProfiler` scans recordings and computes per-field variance. `RelationshipValidator` checks structural invariants from a hand-written catalog. Profile-aware mode extends `ProtoDiffer` to use variance context.

**Tech Stack:** Kotlin, protobuf-java reflection (`Message.getAllFields()`), Kotest FunSpec.

**Spec:** `docs/plans/2026-03-22-conformance-observatory.md`

---

## File Map

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `tooling/.../conformance/Segment.kt` | Data types: Segment, SegmentProfile, FieldProfile, AnnotationPresence, ValueVariance, Confidence |
| Create | `tooling/.../conformance/SegmentDetector.kt` | Find and categorize segments in recording frames |
| Create | `tooling/.../conformance/FieldVarianceProfiler.kt` | Compute per-field variance from N segment instances |
| Create | `tooling/.../conformance/SegmentVarianceMain.kt` | CLI: `just segment-variance` |
| Create | `tooling/.../conformance/Relationship.kt` | Relationship sealed class + ValidationResult |
| Create | `tooling/.../conformance/RelationshipCatalog.kt` | Hand-written pattern catalog |
| Create | `tooling/.../conformance/RelationshipValidator.kt` | Validate patterns against segment instances |
| Create | `tooling/.../conformance/RelationshipMain.kt` | CLI: `just segment-relationships` |
| Modify | `tooling/.../conformance/ProtoDiffer.kt` | Add optional profile-aware diff mode |
| Modify | `tooling/.../conformance/ProtoConformMain.kt` | Add `--profile` flag |
| Modify | `just/proto.just` | Add `segment-variance` and `segment-relationships` recipes |
| Create | `tooling/src/test/.../conformance/SegmentDetectorTest.kt` | Segment detection tests |
| Create | `tooling/src/test/.../conformance/FieldVarianceProfilerTest.kt` | Variance profiling tests |
| Create | `tooling/src/test/.../conformance/RelationshipValidatorTest.kt` | Relationship validation tests |

All paths under `tooling/src/main/kotlin/leyline/conformance/` unless noted. Test paths under `tooling/src/test/kotlin/leyline/conformance/`.

---

### Task 1: Data types

**Files:**
- Create: `tooling/src/main/kotlin/leyline/conformance/Segment.kt`

- [ ] **Step 1: Write all data types**

```kotlin
package leyline.conformance

import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/** A single observed protocol interaction from a recording. */
data class Segment(
    /** Mechanic category: "CastSpell", "PlayLand", or message type: "SearchReq" */
    val category: String,
    /** The GRE message (GSM Diff for mechanics, full message for standalone) */
    val message: GREToClientMessage,
    /** Provenance */
    val session: String,
    val frameIndex: Int,
    val gsId: Int,
)

/** Variance profile for a segment category, computed from N instances. */
data class SegmentProfile(
    val category: String,
    val instanceCount: Int,
    val sessionCount: Int,
    val confidence: Confidence,
    val fieldProfiles: Map<String, FieldProfile>,
    val annotationPresence: Map<String, AnnotationPresence>,
)

/** Per-field variance observed across instances. */
data class FieldProfile(
    /** Fraction of instances where this field is present (1.0 = always) */
    val frequency: Double,
    /** How the value varies across instances */
    val variance: ValueVariance,
    /** Up to 5 unique values observed */
    val samples: List<String>,
)

enum class ValueVariance {
    /** Same value in all instances */
    CONSTANT,
    /** Small set of distinct values (≤10) */
    ENUM,
    /** Always different, instance-ID-like */
    ID,
    /** Numeric, varies within a range */
    RANGED,
    /** List/repeated field with varying length */
    SIZED,
}

/** How much an annotation type appears in a segment category. */
data class AnnotationPresence(
    val frequency: Double,
    val instanceCount: Int,
    val coOccursWith: Set<String>,
)

enum class Confidence {
    /** < 3 instances — shown but not reliable */
    TENTATIVE,
    /** ≥ 3 instances across ≥ 2 sessions */
    OBSERVED,
    /** ≥ 10 instances across ≥ 3 sessions */
    CONFIDENT,
    ;

    companion object {
        fun from(instances: Int, sessions: Int): Confidence = when {
            instances >= 10 && sessions >= 3 -> CONFIDENT
            instances >= 3 && sessions >= 2 -> OBSERVED
            else -> TENTATIVE
        }
    }
}
```

- [ ] **Step 2: Run compilation check**

Run: `./gradlew :tooling:compileKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```
feat(conformance): add Segment, SegmentProfile, FieldProfile data types
```

---

### Task 2: SegmentDetector

**Files:**
- Create: `tooling/src/main/kotlin/leyline/conformance/SegmentDetector.kt`
- Create: `tooling/src/test/kotlin/leyline/conformance/SegmentDetectorTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import wotc.mtgo.gre.external.messaging.Messages.*

class SegmentDetectorTest : FunSpec({

    test("detects CastSpell segment from GSM with ZoneTransfer annotation") {
        val zt = AnnotationInfo.newBuilder()
            .addType(AnnotationType.ZoneTransfer_af5a)
            .addDetails(
                KeyValuePairInfo.newBuilder()
                    .setKey("category").setType(KeyValuePairValueType.String)
                    .addValueString("CastSpell"),
            )
            .build()
        val gsm = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .addAnnotations(zt)
            .build()
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateMessage(gsm)
            .setGameStateId(10)
            .build()
        val frame = RecordingFrameLoader.IndexedGREMessage(42, gre)

        val segments = SegmentDetector.detect(listOf(frame), "test-session")

        segments.size shouldBe 1
        segments[0].category shouldBe "CastSpell"
        segments[0].frameIndex shouldBe 42
        segments[0].gsId shouldBe 10
    }

    test("detects standalone SearchReq as its own segment") {
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.SearchReq_695e)
            .setGameStateId(10)
            .setSearchReq(SearchReq.newBuilder().setMaxFind(1))
            .build()
        val frame = RecordingFrameLoader.IndexedGREMessage(99, gre)

        val segments = SegmentDetector.detect(listOf(frame), "test-session")

        segments.size shouldBe 1
        segments[0].category shouldBe "SearchReq"
    }

    test("skips GSM without ZoneTransfer") {
        val gsm = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .addAnnotations(
                AnnotationInfo.newBuilder().addType(AnnotationType.PhaseOrStepModified),
            )
            .build()
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateMessage(gsm)
            .build()
        val frame = RecordingFrameLoader.IndexedGREMessage(1, gre)

        val segments = SegmentDetector.detect(listOf(frame), "test-session")
        segments.size shouldBe 0
    }

    test("groups segments by category") {
        val session = "2026-03-21_22-05-00"
        if (!java.io.File("recordings/$session").exists()) return@test

        val frames = RecordingFrameLoader.load(session, seat = 0)
        val segments = SegmentDetector.detect(frames, session)
        val grouped = SegmentDetector.groupByCategory(segments)

        // Bushwhack recording has CastSpell, PlayLand, and SearchReq at minimum
        grouped.keys.shouldNotBeEmpty()
        grouped.forEach { (cat, segs) ->
            segs.shouldNotBeEmpty()
            segs.forEach { it.category shouldBe cat }
        }
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `just test-one SegmentDetectorTest`

Expected: FAIL — `SegmentDetector` doesn't exist.

- [ ] **Step 3: Implement SegmentDetector**

```kotlin
package leyline.conformance

import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Identifies and categorizes protocol segments from raw recording frames.
 *
 * A segment is either:
 * - A GSM Diff containing a ZoneTransfer annotation (mechanic: CastSpell, PlayLand, etc.)
 * - A standalone non-GSM message (SearchReq, DeclareAttackersReq, CastingTimeOptionsReq, etc.)
 */
object SegmentDetector {

    /** Standalone message types that are their own segment. */
    private val STANDALONE_TYPES = setOf(
        GREMessageType.SearchReq_695e,
        GREMessageType.DeclareAttackersReq_695e,
        GREMessageType.DeclareBlockersReq_695e,
        GREMessageType.SelectTargetsReq_695e,
        GREMessageType.SelectNreq,
        GREMessageType.CastingTimeOptionsReq_695e,
        GREMessageType.GroupReq_695e,
        GREMessageType.IntermissionReq_695e,
        GREMessageType.ConnectResp_695e,
        GREMessageType.MulliganReq_aa0d,
    )

    private val PROTO_SUFFIX = Regex("_[a-f0-9]{3,4}$")

    /** Detect all segments in a list of recording frames. */
    fun detect(
        frames: List<RecordingFrameLoader.IndexedGREMessage>,
        session: String,
    ): List<Segment> {
        val segments = mutableListOf<Segment>()

        for (frame in frames) {
            val msg = frame.message

            // Standalone messages
            if (msg.type in STANDALONE_TYPES) {
                val category = msg.type.name.replace(PROTO_SUFFIX, "")
                segments.add(Segment(category, msg, session, frame.frameIndex, msg.gameStateId))
                continue
            }

            // GSM Diffs with ZoneTransfer
            if (msg.type == GREMessageType.GameStateMessage_695e && msg.hasGameStateMessage()) {
                val gsm = msg.gameStateMessage
                if (gsm.type != GameStateType.Diff) continue

                val ztCategory = extractZoneTransferCategory(gsm)
                if (ztCategory != null) {
                    segments.add(Segment(ztCategory, msg, session, frame.frameIndex, msg.gameStateId))
                }
            }
        }
        return segments
    }

    /** Group segments by category. */
    fun groupByCategory(segments: List<Segment>): Map<String, List<Segment>> =
        segments.groupBy { it.category }

    /** Scan all available recording sessions, return all segments. */
    fun scanAll(seat: Int = 0): List<Segment> {
        val recordingsDir = java.io.File("recordings")
        if (!recordingsDir.isDirectory) return emptyList()

        return recordingsDir.listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("\\d{4}-.*")) }
            ?.sortedBy { it.name }
            ?.flatMap { sessionDir ->
                val session = sessionDir.name
                val frames = RecordingFrameLoader.load(session, seat)
                detect(frames, session)
            }
            ?: emptyList()
    }

    /** Extract ZoneTransfer category from a GSM's annotations. */
    private fun extractZoneTransferCategory(gsm: GameStateMessage): String? {
        for (ann in gsm.annotationsList) {
            val isZoneTransfer = ann.typeList.any {
                it == AnnotationType.ZoneTransfer_af5a
            }
            if (!isZoneTransfer) continue

            for (detail in ann.detailsList) {
                if (detail.key == "category" && detail.valueStringList.isNotEmpty()) {
                    return detail.valueStringList[0]
                }
            }
        }
        return null
    }
}
```

- [ ] **Step 4: Run tests**

Run: `just test-one SegmentDetectorTest`

Expected: PASS.

- [ ] **Step 5: Format and commit**

Run: `just fmt`

```
feat(conformance): add SegmentDetector — categorize recording frames into segments
```

---

### Task 3: FieldVarianceProfiler

**Files:**
- Create: `tooling/src/main/kotlin/leyline/conformance/FieldVarianceProfiler.kt`
- Create: `tooling/src/test/kotlin/leyline/conformance/FieldVarianceProfilerTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import wotc.mtgo.gre.external.messaging.Messages.*

class FieldVarianceProfilerTest : FunSpec({

    test("constant field detected") {
        val segments = (1..5).map { i ->
            makeSegment(
                GameStateMessage.newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(i)
                    .build(),
            )
        }
        val profile = FieldVarianceProfiler.profile(segments)

        profile.fieldProfiles["gameStateMessage.type"]!!.variance shouldBe ValueVariance.CONSTANT
        profile.fieldProfiles["gameStateMessage.type"]!!.frequency shouldBeExactly 1.0
    }

    test("enum field detected for small value set") {
        val segments = listOf(
            makeGsmSegment(turnPhase = "Main1"),
            makeGsmSegment(turnPhase = "Main2"),
            makeGsmSegment(turnPhase = "Main1"),
            makeGsmSegment(turnPhase = "Main1"),
        )
        val profile = FieldVarianceProfiler.profile(segments)

        val turnPhaseProfile = profile.fieldProfiles.entries.firstOrNull {
            it.key.contains("phase")
        }
        // Phase field should be ENUM (2 distinct values)
        if (turnPhaseProfile != null) {
            turnPhaseProfile.value.variance shouldBe ValueVariance.ENUM
        }
    }

    test("annotation presence tracked") {
        val segments = (1..5).map { i ->
            val ann = AnnotationInfo.newBuilder()
                .addType(AnnotationType.ZoneTransfer_af5a)
                .addType(AnnotationType.ObjectIdChanged)
            makeSegment(
                GameStateMessage.newBuilder()
                    .setType(GameStateType.Diff)
                    .addAnnotations(ann)
                    .build(),
            )
        }
        val profile = FieldVarianceProfiler.profile(segments)

        profile.annotationPresence["ZoneTransfer"]!!.frequency shouldBeExactly 1.0
        profile.annotationPresence["ObjectIdChanged"]!!.frequency shouldBeExactly 1.0
    }

    test("sometimes-present annotation has frequency < 1") {
        val withManaPaid = (1..3).map {
            makeSegment(
                GameStateMessage.newBuilder().setType(GameStateType.Diff)
                    .addAnnotations(AnnotationInfo.newBuilder().addType(AnnotationType.ZoneTransfer_af5a))
                    .addAnnotations(AnnotationInfo.newBuilder().addType(AnnotationType.ManaPaid))
                    .build(),
            )
        }
        val withoutManaPaid = (1..2).map {
            makeSegment(
                GameStateMessage.newBuilder().setType(GameStateType.Diff)
                    .addAnnotations(AnnotationInfo.newBuilder().addType(AnnotationType.ZoneTransfer_af5a))
                    .build(),
            )
        }
        val profile = FieldVarianceProfiler.profile(withManaPaid + withoutManaPaid)

        profile.annotationPresence["ZoneTransfer"]!!.frequency shouldBeExactly 1.0
        profile.annotationPresence["ManaPaid"]!!.frequency shouldBe 0.6
    }

    test("confidence levels") {
        Confidence.from(instances = 2, sessions = 1) shouldBe Confidence.TENTATIVE
        Confidence.from(instances = 5, sessions = 2) shouldBe Confidence.OBSERVED
        Confidence.from(instances = 15, sessions = 4) shouldBe Confidence.CONFIDENT
    }
})

// --- Test helpers ---

private var segCounter = 0

private fun makeSegment(gsm: GameStateMessage, session: String = "test"): Segment {
    val gre = GREToClientMessage.newBuilder()
        .setType(GREMessageType.GameStateMessage_695e)
        .setGameStateMessage(gsm)
        .setGameStateId(gsm.gameStateId)
        .build()
    return Segment("Test", gre, session, ++segCounter, gsm.gameStateId)
}

private fun makeGsmSegment(turnPhase: String, session: String = "test"): Segment {
    val phase = Phase.values().firstOrNull { it.name.contains(turnPhase, ignoreCase = true) } ?: Phase.Main1_a549
    val gsm = GameStateMessage.newBuilder()
        .setType(GameStateType.Diff)
        .setTurnInfo(TurnInfo.newBuilder().setPhase(phase))
        .build()
    return makeSegment(gsm, session)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `just test-one FieldVarianceProfilerTest`

Expected: FAIL.

- [ ] **Step 3: Implement FieldVarianceProfiler**

The profiler walks each segment's proto via `Message.getAllFields()`, collects values per field path, then classifies the variance.

```kotlin
package leyline.conformance

import com.google.protobuf.Message

/**
 * Computes per-field variance profiles from a collection of segment instances.
 *
 * For each field path in the proto message, determines: how often it's present,
 * whether its value is constant/enum/ID/ranged/sized, and collects samples.
 */
object FieldVarianceProfiler {

    private val PROTO_SUFFIX = Regex("_[a-f0-9]{3,4}$")

    /** Fields to skip — session-specific metadata, not protocol-meaningful. */
    private val SKIP_FIELDS = setOf(
        "gameStateId", "prevGameStateId", "msgId", "matchID",
        "timestamp", "transactionId", "requestId",
    )

    /** Fields known to carry instance IDs. */
    private val ID_FIELDS = setOf(
        "instanceId", "affectorId", "affectedIds", "objectInstanceIds",
        "sourceId", "itemsToSearch", "itemsSought", "targetInstanceId",
        "attackerInstanceId", "blockerInstanceId", "attackerIds",
        "targetId", "parentId",
    )

    fun profile(segments: List<Segment>): SegmentProfile {
        if (segments.isEmpty()) return emptyProfile("Empty")

        val category = segments.first().category
        val sessions = segments.map { it.session }.toSet()
        val n = segments.size

        // Collect field values across all instances
        val fieldValues = mutableMapOf<String, MutableList<String>>() // path -> values (as strings)
        val fieldPresence = mutableMapOf<String, Int>() // path -> count of instances where present

        for (segment in segments) {
            val presentPaths = mutableSetOf<String>()
            walkMessage(segment.message, "", presentPaths, fieldValues)
            for (path in presentPaths) {
                fieldPresence[path] = (fieldPresence[path] ?: 0) + 1
            }
        }

        // Build field profiles
        val fieldProfiles = fieldValues.mapNotNull { (path, values) ->
            val fieldName = path.substringAfterLast(".")
            if (fieldName in SKIP_FIELDS) return@mapNotNull null

            val presence = fieldPresence[path] ?: 0
            val frequency = presence.toDouble() / n
            val distinct = values.toSet()
            val variance = classifyVariance(fieldName, distinct, values.size)
            val samples = distinct.take(5).toList()

            path to FieldProfile(frequency, variance, samples)
        }.toMap()

        // Compute annotation presence
        val annotationPresence = computeAnnotationPresence(segments)

        return SegmentProfile(
            category = category,
            instanceCount = n,
            sessionCount = sessions.size,
            confidence = Confidence.from(n, sessions.size),
            fieldProfiles = fieldProfiles,
            annotationPresence = annotationPresence,
        )
    }

    private fun classifyVariance(fieldName: String, distinct: Set<String>, totalCount: Int): ValueVariance {
        if (fieldName in ID_FIELDS) return ValueVariance.ID
        if (distinct.size == 1) return ValueVariance.CONSTANT
        if (distinct.size <= 10) return ValueVariance.ENUM
        // Check if numeric range
        val numbers = distinct.mapNotNull { it.toIntOrNull() }
        if (numbers.size == distinct.size) return ValueVariance.RANGED
        return ValueVariance.SIZED
    }

    /** Walk a proto message, collecting field values by path. */
    private fun walkMessage(
        msg: Message,
        prefix: String,
        presentPaths: MutableSet<String>,
        fieldValues: MutableMap<String, MutableList<String>>,
    ) {
        for ((field, value) in msg.allFields) {
            val path = if (prefix.isEmpty()) field.name else "$prefix.${field.name}"
            presentPaths.add(path)

            when {
                field.isRepeated -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = value as List<Any>
                    // Track size as a value for SIZED detection
                    fieldValues.getOrPut("$path._size") { mutableListOf() }.add(list.size.toString())
                    presentPaths.add("$path._size")

                    for (item in list) {
                        if (item is Message) {
                            walkMessage(item, path, presentPaths, fieldValues)
                        } else {
                            fieldValues.getOrPut(path) { mutableListOf() }.add(item.toString())
                        }
                    }
                }
                value is Message -> walkMessage(value, path, presentPaths, fieldValues)
                else -> fieldValues.getOrPut(path) { mutableListOf() }.add(value.toString())
            }
        }
    }

    private fun computeAnnotationPresence(segments: List<Segment>): Map<String, AnnotationPresence> {
        val n = segments.size
        val typeCounts = mutableMapOf<String, Int>()
        val coOccurrence = mutableMapOf<String, MutableMap<String, Int>>()

        for (segment in segments) {
            if (!segment.message.hasGameStateMessage()) continue
            val gsm = segment.message.gameStateMessage
            val typesInSegment = mutableSetOf<String>()

            for (ann in gsm.annotationsList + gsm.persistentAnnotationsList) {
                for (type in ann.typeList) {
                    typesInSegment.add(type.name.replace(PROTO_SUFFIX, ""))
                }
            }

            for (type in typesInSegment) {
                typeCounts[type] = (typeCounts[type] ?: 0) + 1
            }

            // Track co-occurrence
            for (a in typesInSegment) {
                for (b in typesInSegment) {
                    if (a != b) {
                        coOccurrence.getOrPut(a) { mutableMapOf() }
                            .merge(b, 1) { old, new -> old + new }
                    }
                }
            }
        }

        return typeCounts.map { (type, count) ->
            val frequency = count.toDouble() / n
            val coOccurs = coOccurrence[type]
                ?.filter { it.value == count } // only types that ALWAYS co-occur
                ?.keys?.toSet()
                ?: emptySet()
            type to AnnotationPresence(frequency, count, coOccurs)
        }.toMap()
    }

    private fun emptyProfile(category: String) = SegmentProfile(
        category, 0, 0, Confidence.TENTATIVE, emptyMap(), emptyMap(),
    )
}
```

- [ ] **Step 4: Run tests**

Run: `just test-one FieldVarianceProfilerTest`

Expected: PASS.

- [ ] **Step 5: Format and commit**

Run: `just fmt`

```
feat(conformance): add FieldVarianceProfiler — per-field variance from segment instances
```

---

### Task 4: SegmentVarianceMain CLI

**Files:**
- Create: `tooling/src/main/kotlin/leyline/conformance/SegmentVarianceMain.kt`
- Modify: `just/proto.just`

- [ ] **Step 1: Implement CLI**

```kotlin
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
    val sessionFlag = flagValue(args.toList(), "--session")

    // Detect all segments
    val allSegments = if (sessionFlag != null) {
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
                println("  %-35s %d/%d (%.0f%%)".format(
                    type, pres.instanceCount, p.instanceCount, pres.frequency * 100))
            }
            println()
        }
    }

    // Field profiles — group by variance type
    println("FIELDS:")
    val constant = p.fieldProfiles.filter { it.value.variance == ValueVariance.CONSTANT }
    val enums = p.fieldProfiles.filter { it.value.variance == ValueVariance.ENUM }
    val ids = p.fieldProfiles.filter { it.value.variance == ValueVariance.ID }
    val other = p.fieldProfiles.filter { it.value.variance !in setOf(ValueVariance.CONSTANT, ValueVariance.ENUM, ValueVariance.ID) }

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

private fun flagValue(args: List<String>, flag: String): String? {
    val idx = args.indexOf(flag)
    return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
}
```

- [ ] **Step 2: Add just recipe**

Add to `just/proto.just`:

```just
# segment variance: field presence/variance per mechanic across recordings
# Usage: just segment-variance [CastSpell] [--session 2026-03-21_22-05-00]
[group('proto')]
segment-variance *args: (_require classpath) check-java
    @{{_cli}} leyline.conformance.SegmentVarianceMainKt {{args}}
```

- [ ] **Step 3: Build and verify**

Run: `just build && just segment-variance`

Expected: List of all categories with instance counts from available recordings.

Run: `just segment-variance CastSpell`

Expected: Detailed variance profile for CastSpell segments.

- [ ] **Step 4: Format and commit**

Run: `just fmt`

```
feat(conformance): add just segment-variance CLI for field variance profiling
```

---

### Task 5: Relationship types and catalog

**Files:**
- Create: `tooling/src/main/kotlin/leyline/conformance/Relationship.kt`
- Create: `tooling/src/main/kotlin/leyline/conformance/RelationshipCatalog.kt`

- [ ] **Step 1: Write Relationship sealed class and ValidationResult**

```kotlin
package leyline.conformance

/** A structural invariant that should hold across all instances of a segment category. */
sealed class Relationship(open val category: String) {

    /** Field A == Field B within the same segment (after ID normalization). */
    data class Equals(
        override val category: String,
        val a: String,
        val b: String,
    ) : Relationship(category)

    /** Field or list is present and non-empty. */
    data class NonEmpty(
        override val category: String,
        val path: String,
    ) : Relationship(category)

    /** Field value is one of an expected set. */
    data class ValueIn(
        override val category: String,
        val path: String,
        val values: Set<String>,
    ) : Relationship(category)

    /** Annotation type appears in all instances of this category. */
    data class AlwaysPresent(
        override val category: String,
        val annotationType: String,
    ) : Relationship(category)
}

data class ValidationResult(
    val pattern: Relationship,
    val holds: Int,
    val total: Int,
    val exceptions: List<ValidationException>,
) {
    val confidence get() = if (total == 0) 0.0 else holds.toDouble() / total

    val status
        get() = when {
            total == 0 -> ValidationStatus.NO_DATA
            holds == total -> ValidationStatus.HOLDS
            confidence >= 0.95 -> ValidationStatus.MOSTLY_HOLDS
            else -> ValidationStatus.VIOLATED
        }
}

data class ValidationException(
    val session: String,
    val gsId: Int,
    val frameIndex: Int,
    val detail: String,
)

enum class ValidationStatus {
    HOLDS,
    MOSTLY_HOLDS,
    VIOLATED,
    NO_DATA,
    UNRESOLVABLE,
}
```

- [ ] **Step 2: Write initial RelationshipCatalog**

```kotlin
package leyline.conformance

/**
 * Hand-written structural invariants observed in real server recordings.
 *
 * Patterns are validated against recording data by [RelationshipValidator].
 * Grow this catalog as bugs reveal new invariants.
 */
object RelationshipCatalog {

    val patterns: List<Relationship> = listOf(
        // --- CastSpell ---
        Relationship.AlwaysPresent("CastSpell", "ObjectIdChanged"),
        Relationship.AlwaysPresent("CastSpell", "ZoneTransfer"),
        Relationship.ValueIn("CastSpell", "annotations[ZoneTransfer].details.zone_dest", setOf("27")),
        Relationship.ValueIn("CastSpell", "annotations[ZoneTransfer].details.category", setOf("CastSpell")),
        Relationship.NonEmpty("CastSpell", "gameObjects"),

        // --- PlayLand ---
        Relationship.AlwaysPresent("PlayLand", "ObjectIdChanged"),
        Relationship.AlwaysPresent("PlayLand", "ZoneTransfer"),
        Relationship.ValueIn("PlayLand", "annotations[ZoneTransfer].details.zone_dest", setOf("28")),
        Relationship.ValueIn("PlayLand", "annotations[ZoneTransfer].details.category", setOf("PlayLand")),

        // --- Resolve ---
        Relationship.AlwaysPresent("Resolve", "ZoneTransfer"),
        Relationship.AlwaysPresent("Resolve", "ResolutionComplete"),

        // --- SearchReq ---
        Relationship.NonEmpty("SearchReq", "searchReq.itemsToSearch"),
        Relationship.NonEmpty("SearchReq", "searchReq.itemsSought"),
        Relationship.NonEmpty("SearchReq", "searchReq.zonesToSearch"),

        // --- Draw ---
        Relationship.AlwaysPresent("Draw", "ZoneTransfer"),
        Relationship.AlwaysPresent("Draw", "ObjectIdChanged"),
        Relationship.ValueIn("Draw", "annotations[ZoneTransfer].details.category", setOf("Draw")),
    )

    fun forCategory(category: String): List<Relationship> =
        patterns.filter { it.category == category }
}
```

- [ ] **Step 3: Compile check**

Run: `./gradlew :tooling:compileKotlin`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```
feat(conformance): add Relationship types and initial pattern catalog
```

---

### Task 6: RelationshipValidator

**Files:**
- Create: `tooling/src/main/kotlin/leyline/conformance/RelationshipValidator.kt`
- Create: `tooling/src/test/kotlin/leyline/conformance/RelationshipValidatorTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import wotc.mtgo.gre.external.messaging.Messages.*

class RelationshipValidatorTest : FunSpec({

    test("AlwaysPresent holds when annotation type is in every segment") {
        val segments = (1..3).map {
            castSpellSegment(withObjectIdChanged = true)
        }
        val pattern = Relationship.AlwaysPresent("CastSpell", "ObjectIdChanged")

        val result = RelationshipValidator.validate(pattern, segments)

        result.status shouldBe ValidationStatus.HOLDS
        result.holds shouldBe 3
        result.total shouldBe 3
    }

    test("AlwaysPresent violated when annotation missing from some segments") {
        val withOIC = castSpellSegment(withObjectIdChanged = true)
        val withoutOIC = castSpellSegment(withObjectIdChanged = false)

        val pattern = Relationship.AlwaysPresent("CastSpell", "ObjectIdChanged")
        val result = RelationshipValidator.validate(pattern, listOf(withOIC, withOIC, withoutOIC))

        result.status shouldBe ValidationStatus.VIOLATED
        result.holds shouldBe 2
        result.total shouldBe 3
        result.exceptions.size shouldBe 1
    }

    test("NonEmpty holds for non-empty list") {
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.SearchReq_695e)
            .setSearchReq(SearchReq.newBuilder().addItemsToSearch(100).addItemsToSearch(200))
            .build()
        val segment = Segment("SearchReq", gre, "test", 1, 10)

        val pattern = Relationship.NonEmpty("SearchReq", "searchReq.itemsToSearch")
        val result = RelationshipValidator.validate(pattern, listOf(segment))

        result.status shouldBe ValidationStatus.HOLDS
    }

    test("NonEmpty violated for empty list") {
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.SearchReq_695e)
            .setSearchReq(SearchReq.getDefaultInstance())
            .build()
        val segment = Segment("SearchReq", gre, "test", 1, 10)

        val pattern = Relationship.NonEmpty("SearchReq", "searchReq.itemsToSearch")
        val result = RelationshipValidator.validate(pattern, listOf(segment))

        result.status shouldBe ValidationStatus.VIOLATED
    }

    test("ValueIn holds when detail value matches") {
        val segment = castSpellSegment(zoneDest = "27")
        val pattern = Relationship.ValueIn(
            "CastSpell",
            "annotations[ZoneTransfer].details.zone_dest",
            setOf("27"),
        )
        val result = RelationshipValidator.validate(pattern, listOf(segment))

        result.status shouldBe ValidationStatus.HOLDS
    }

    test("ValueIn violated when value not in set") {
        val segment = castSpellSegment(zoneDest = "28")
        val pattern = Relationship.ValueIn(
            "CastSpell",
            "annotations[ZoneTransfer].details.zone_dest",
            setOf("27"),
        )
        val result = RelationshipValidator.validate(pattern, listOf(segment))

        result.status shouldBe ValidationStatus.VIOLATED
    }

    test("validate all patterns for a category against recording") {
        val session = "2026-03-21_22-05-00"
        if (!java.io.File("recordings/$session").exists()) return@test

        val frames = RecordingFrameLoader.load(session, seat = 0)
        val segments = SegmentDetector.detect(frames, session)
        val castSpells = segments.filter { it.category == "CastSpell" }

        val patterns = RelationshipCatalog.forCategory("CastSpell")
        val results = patterns.map { RelationshipValidator.validate(it, castSpells) }

        // All CastSpell patterns should hold against real recording
        for (result in results) {
            if (result.status == ValidationStatus.VIOLATED) {
                println("VIOLATED: ${result.pattern} — ${result.holds}/${result.total}")
                for (ex in result.exceptions) println("  ${ex.session} gsId=${ex.gsId}: ${ex.detail}")
            }
        }
    }
})

// --- Test helpers ---

private var counter = 0

private fun castSpellSegment(
    withObjectIdChanged: Boolean = true,
    zoneDest: String = "27",
): Segment {
    val gsmBuilder = GameStateMessage.newBuilder().setType(GameStateType.Diff)

    val zt = AnnotationInfo.newBuilder()
        .addType(AnnotationType.ZoneTransfer_af5a)
        .addDetails(
            KeyValuePairInfo.newBuilder().setKey("category")
                .setType(KeyValuePairValueType.String).addValueString("CastSpell"),
        )
        .addDetails(
            KeyValuePairInfo.newBuilder().setKey("zone_dest")
                .setType(KeyValuePairValueType.Uint32).addValueUint32(zoneDest.toInt()),
        )
    gsmBuilder.addAnnotations(zt)

    if (withObjectIdChanged) {
        gsmBuilder.addAnnotations(
            AnnotationInfo.newBuilder().addType(AnnotationType.ObjectIdChanged),
        )
    }

    val gre = GREToClientMessage.newBuilder()
        .setType(GREMessageType.GameStateMessage_695e)
        .setGameStateMessage(gsmBuilder)
        .setGameStateId(++counter)
        .build()
    return Segment("CastSpell", gre, "test", counter, counter)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `just test-one RelationshipValidatorTest`

Expected: FAIL.

- [ ] **Step 3: Implement RelationshipValidator**

```kotlin
package leyline.conformance

import com.google.protobuf.Message
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Validates [Relationship] patterns against observed segment instances.
 *
 * Each pattern is checked against every segment instance. Returns a
 * [ValidationResult] with hold count, exceptions, and provenance.
 */
object RelationshipValidator {

    private val PROTO_SUFFIX = Regex("_[a-f0-9]{3,4}$")

    fun validate(pattern: Relationship, segments: List<Segment>): ValidationResult {
        var holds = 0
        val exceptions = mutableListOf<ValidationException>()

        for (segment in segments) {
            val result = checkOne(pattern, segment)
            if (result == null) {
                holds++
            } else {
                exceptions.add(
                    ValidationException(segment.session, segment.gsId, segment.frameIndex, result),
                )
            }
        }
        return ValidationResult(pattern, holds, segments.size, exceptions)
    }

    fun validateAll(
        patterns: List<Relationship>,
        segments: List<Segment>,
    ): List<ValidationResult> {
        val grouped = segments.groupBy { it.category }
        return patterns.map { pattern ->
            val relevant = grouped[pattern.category] ?: emptyList()
            validate(pattern, relevant)
        }
    }

    /** Check one pattern against one segment. Returns null if holds, error detail if violated. */
    private fun checkOne(pattern: Relationship, segment: Segment): String? = when (pattern) {
        is Relationship.AlwaysPresent -> checkAlwaysPresent(pattern, segment)
        is Relationship.NonEmpty -> checkNonEmpty(pattern, segment)
        is Relationship.ValueIn -> checkValueIn(pattern, segment)
        is Relationship.Equals -> checkEquals(pattern, segment)
    }

    private fun checkAlwaysPresent(pattern: Relationship.AlwaysPresent, segment: Segment): String? {
        if (!segment.message.hasGameStateMessage()) return "no gameStateMessage"
        val gsm = segment.message.gameStateMessage
        val allAnnotations = gsm.annotationsList + gsm.persistentAnnotationsList
        val hasType = allAnnotations.any { ann ->
            ann.typeList.any { it.name.replace(PROTO_SUFFIX, "") == pattern.annotationType }
        }
        return if (hasType) null else "annotation type ${pattern.annotationType} not found"
    }

    private fun checkNonEmpty(pattern: Relationship.NonEmpty, segment: Segment): String? {
        val value = resolveFieldPath(segment.message, pattern.path)
        return when {
            value == null -> "path ${pattern.path} not found"
            value is List<*> && value.isEmpty() -> "list at ${pattern.path} is empty"
            value is List<*> -> null // non-empty list
            value is Message -> null // present message
            value is Number && value.toInt() == 0 -> "value at ${pattern.path} is 0"
            else -> null // present and non-default
        }
    }

    private fun checkValueIn(pattern: Relationship.ValueIn, segment: Segment): String? {
        // Handle annotation detail paths: annotations[TypeName].details.key
        val annotationMatch = Regex("annotations\\[(.+?)]\\.(.*)")
            .matchEntire(pattern.path)
        if (annotationMatch != null) {
            return checkAnnotationDetailValue(
                annotationMatch.groupValues[1],
                annotationMatch.groupValues[2],
                pattern.values,
                segment,
            )
        }

        val value = resolveFieldPath(segment.message, pattern.path)
        return when {
            value == null -> "path ${pattern.path} not found"
            value.toString() in pattern.values -> null
            else -> "value '${value}' not in ${pattern.values}"
        }
    }

    private fun checkAnnotationDetailValue(
        annotationType: String,
        detailPath: String,
        expectedValues: Set<String>,
        segment: Segment,
    ): String? {
        if (!segment.message.hasGameStateMessage()) return "no gameStateMessage"
        val gsm = segment.message.gameStateMessage
        val allAnnotations = gsm.annotationsList + gsm.persistentAnnotationsList

        // Find annotation with matching type
        val ann = allAnnotations.firstOrNull { a ->
            a.typeList.any { it.name.replace(PROTO_SUFFIX, "") == annotationType }
        } ?: return "annotation type $annotationType not found"

        // Parse detail path: "details.key_name"
        val keyName = detailPath.removePrefix("details.")

        // Find the detail
        val detail = ann.detailsList.firstOrNull { it.key == keyName }
            ?: return "detail key '$keyName' not found in $annotationType"

        // Extract value
        val value = extractDetailValue(detail)
        return if (value in expectedValues) null
        else "detail '$keyName' value '$value' not in $expectedValues"
    }

    private fun extractDetailValue(detail: KeyValuePairInfo): String = when {
        detail.valueStringList.isNotEmpty() -> detail.valueStringList[0]
        detail.valueUint32List.isNotEmpty() -> detail.valueUint32List[0].toString()
        detail.valueInt32List.isNotEmpty() -> detail.valueInt32List[0].toString()
        else -> ""
    }

    private fun checkEquals(pattern: Relationship.Equals, segment: Segment): String? {
        val valA = resolveFieldPath(segment.message, pattern.a)
        val valB = resolveFieldPath(segment.message, pattern.b)
        return when {
            valA == null -> "path ${pattern.a} not found"
            valB == null -> "path ${pattern.b} not found"
            valA.toString() == valB.toString() -> null
            else -> "${pattern.a}='$valA' != ${pattern.b}='$valB'"
        }
    }

    /** Resolve a dot-separated field path on a proto message. Returns List for repeated fields. */
    private fun resolveFieldPath(msg: Message, path: String): Any? {
        val parts = path.split(".")
        var current: Any = msg

        for (part in parts) {
            when (current) {
                is Message -> {
                    val field = current.descriptorForType.findFieldByName(part) ?: return null
                    current = current.getField(field)
                }
                is List<*> -> return current // can't descend further into a list
                else -> return null
            }
        }
        return current
    }
}
```

- [ ] **Step 4: Run tests**

Run: `just test-one RelationshipValidatorTest`

Expected: PASS.

- [ ] **Step 5: Format and commit**

Run: `just fmt`

```
feat(conformance): add RelationshipValidator — structural invariant checking
```

---

### Task 7: RelationshipMain CLI

**Files:**
- Create: `tooling/src/main/kotlin/leyline/conformance/RelationshipMain.kt`
- Modify: `just/proto.just`

- [ ] **Step 1: Implement CLI**

```kotlin
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
    val category = args.firstOrNull { !it.startsWith("--") }

    val allSegments = SegmentDetector.scanAll(seat = 0)
    val grouped = SegmentDetector.groupByCategory(allSegments)

    val patterns = if (category != null) {
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
    println("Relationship validation — ${results.size} patterns, ${allSegments.size} segments, $sessions sessions")
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
            println("  ? %-50s %d/%d (%.0f%%)".format(
                patternLabel(r.pattern), r.holds, r.total, r.confidence * 100))
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

private fun patternLabel(r: Relationship): String = when (r) {
    is Relationship.AlwaysPresent -> "[${r.category}] AlwaysPresent: ${r.annotationType}"
    is Relationship.NonEmpty -> "[${r.category}] NonEmpty: ${r.path}"
    is Relationship.ValueIn -> "[${r.category}] ValueIn: ${r.path} ∈ ${r.values}"
    is Relationship.Equals -> "[${r.category}] Equals: ${r.a} == ${r.b}"
}
```

- [ ] **Step 2: Add just recipe**

Add to `just/proto.just`:

```just
# relationship validation: check structural invariants against recordings
# Usage: just segment-relationships [CastSpell]
[group('proto')]
segment-relationships *args: (_require classpath) check-java
    @{{_cli}} leyline.conformance.RelationshipMainKt {{args}}
```

- [ ] **Step 3: Build and verify**

Run: `just build && just segment-relationships`

Expected: Validation report showing pattern results against local recordings.

- [ ] **Step 4: Format and commit**

Run: `just fmt`

```
feat(conformance): add just segment-relationships CLI for structural invariant checking
```

---

### Task 8: Profile-aware verification

**Files:**
- Modify: `tooling/src/main/kotlin/leyline/conformance/ProtoDiffer.kt`
- Modify: `tooling/src/main/kotlin/leyline/conformance/ProtoConformMain.kt`

- [ ] **Step 1: Add profile parameter to ProtoDiffer.diff()**

Add to the `diff()` function signature:

```kotlin
fun diff(
    recording: GREToClientMessage,
    engine: GREToClientMessage,
    profile: SegmentProfile? = null,
    seatMap: Map<Int, Int> = emptyMap(),
): DiffResult
```

When `profile` is non-null, adjust comparison logic in the recursive walk:

- Before reporting a field as `missing` from engine: check if `profile.fieldProfiles[path]?.frequency < 1.0`. If so, the field is optional — skip.
- Before comparing a repeated field's values: check the field's `ValueVariance`:
  - `CONSTANT` → exact match
  - `ENUM` → engine value must appear in profile samples
  - `ID` → normalize and compare structure (existing behavior)
  - `SIZED` → skip size comparison, just check non-empty
- After field comparison: validate relationships from `RelationshipCatalog.forCategory(category)` against the engine message.

Add a `relationshipResults: List<ValidationResult>` field to `DiffResult`:

```kotlin
data class DiffResult(
    val missing: List<FieldPath>,
    val extra: List<FieldPath>,
    val mismatched: List<FieldMismatch>,
    val matched: Int,
    val matchedPaths: List<FieldPath> = emptyList(),
    val relationshipResults: List<ValidationResult> = emptyList(),
)
```

Update `report()` to include relationship results when present.

- [ ] **Step 2: Update ProtoConformMain to support --profile flag**

When `--profile` is passed:
1. Detect what category the recording frame belongs to (via `SegmentDetector`)
2. Scan all available recordings for that category
3. Compute `SegmentProfile` via `FieldVarianceProfiler`
4. Pass profile to `ProtoDiffer.diff()`
5. Also validate relationships from `RelationshipCatalog`

- [ ] **Step 3: Test end-to-end**

Run: `just build && just conform-proto SearchReq 2026-03-21_22-05-00 --seat 0 --engine matchdoor/build/conformance/engine-search/ --profile`

Expected: Profile-aware diff showing CONSTANT matches, ENUM matches, SIZED fields handled correctly, relationship results.

- [ ] **Step 4: Run all tooling tests**

Run: `./gradlew :tooling:test --rerun`

Expected: All tests pass (existing + new).

- [ ] **Step 5: Format and commit**

Run: `just fmt`

```
feat(conformance): profile-aware verification in ProtoDiffer + --profile CLI flag
```

---

## Notes for implementer

- **Proto field accessor names:** The proto uses renamed fields via `proto/rename-map.sed`. Kotlin generated classes use the renamed names. Use `addType()` (singular) for AnnotationInfo, not `addTypes()`.
- **Proto suffix stripping:** Annotation type names have suffixes like `_af5a`. Strip with `Regex("_[a-f0-9]{3,4}$")` before comparing. See `AnnotationVariance.kt` line 207 for the pattern.
- **Test recordings:** Tests that require `recordings/` must `return@test` if the directory doesn't exist. The `just` CLI commands work with whatever recordings are locally available.
- **`just test-one`:** Runs `./gradlew :matchdoor:test :tooling:test ... -Pkotest.filter.specs=".*<ClassName>"`. Tooling tests run in `:tooling:test`.
- **`just fmt`:** Run before every commit. Don't manually fix imports.
- **`just build`:** Required before running CLI commands — rebuilds jars and classpath.
- **Field path resolution:** When walking proto messages, `Message.getAllFields()` returns only fields with non-default values (proto3). Empty lists and 0 values are absent. This is correct for variance — an absent field means "not set."
- **The AnnotationVariance.kt system** uses `RecordingDecoder` (lossy JSON path from `capture/payloads/`). This new system uses `RecordingFrameLoader` (raw proto from `capture/seat-N/md-payloads/`). Both paths are valid; they read from different directory structures within the same recording.
- **PROTO_SUFFIX regex** `Regex("_[a-f0-9]{3,4}$")` is duplicated across SegmentDetector, FieldVarianceProfiler, and RelationshipValidator. Extract to a shared constant in `Segment.kt` if the duplication bothers you.
- **Task 8 is intentionally higher-level** than Tasks 1-7. The profile-aware diff integrates all prior pieces and requires judgment calls about how to wire variance types into the recursive walk. The implementer should read the full `ProtoDiffer.kt` source, understand the walk/compareRepeated structure, and integrate profile-checking at the right points. The spec section "Piece 3: Profile-Aware Verification" has the exact rules.
- **`hasGameStateMessage()` guard** — protobuf oneof fields return a default instance (not null) when not set. Always use `hasGameStateMessage()` before accessing `.gameStateMessage`.
- **Read the spec** at `docs/plans/2026-03-22-conformance-observatory.md` for full design rationale.
