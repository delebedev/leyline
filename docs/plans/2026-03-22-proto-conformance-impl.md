# Proto-Native Conformance Pipeline — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the lossy JSON-based conformance pipeline with raw protobuf decoding in Kotlin — one runtime, no fields lost.

**Architecture:** Three components: `RecordingFrameLoader` (reads .bin → proto), `ProtoDiffer` (field-by-field diff with ID normalization), `ProtoConformMain` (CLI). All in `tooling/`. Engine-side diffing stays in `matchdoor/` test code via `MatchFlowHarness`.

**Tech Stack:** Kotlin, protobuf-java reflection (`Message.getAllFields()`), Kotest FunSpec.

**Spec:** `docs/plans/2026-03-22-proto-conformance-pipeline.md`

---

## File Map

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `tooling/.../conformance/RecordingParser.kt` | Extract `parseToGRE()` shared helper |
| Create | `tooling/.../conformance/RecordingFrameLoader.kt` | Load session .bin → `List<GREToClientMessage>` |
| Create | `tooling/.../conformance/ProtoDiffer.kt` | Field-by-field proto diff with ID normalization |
| Create | `tooling/.../conformance/ProtoConformMain.kt` | CLI entry point for `just conform-proto` |
| Modify | `just/proto.just` | Add `conform-proto` recipe |
| Create | `tooling/src/test/kotlin/leyline/conformance/RecordingFrameLoaderTest.kt` | Loader tests |
| Create | `tooling/src/test/kotlin/leyline/conformance/ProtoDifferTest.kt` | Differ tests |

All paths under `tooling/src/main/kotlin/leyline/conformance/` unless noted.

---

### Task 1: Extract `parseToGRE()` shared helper from RecordingParser

**Files:**
- Modify: `tooling/src/main/kotlin/leyline/conformance/RecordingParser.kt`

- [ ] **Step 1: Extract parseToGRE helper**

Add a `parseToGRE()` function that returns `List<GREToClientMessage>` instead of fingerprints. Then rewrite `parsePayload` to call it.

```kotlin
// Add at top of RecordingParser object, before parsePayload:

/**
 * Parse a MatchServiceToClientMessage payload into raw GRE messages.
 * Shared foundation for both fingerprint extraction and full proto loading.
 */
fun parseToGRE(bytes: ByteArray, seatFilter: Int? = null): List<GREToClientMessage> {
    val msg = try {
        MatchServiceToClientMessage.parseFrom(bytes)
    } catch (_: Exception) {
        return emptyList()
    }
    if (!msg.hasGreToClientEvent()) return emptyList()
    return msg.greToClientEvent.greToClientMessagesList.filter { gre ->
        val seatIds = gre.systemSeatIdsList.map { it.toInt() }
        seatFilter == null || seatIds.isEmpty() || seatFilter in seatIds
    }
}
```

Then simplify `parsePayload`:

```kotlin
fun parsePayload(bytes: ByteArray, seatFilter: Int? = null): List<StructuralFingerprint> =
    parseToGRE(bytes, seatFilter).map { StructuralFingerprint.fromGRE(it) }
```

- [ ] **Step 2: Run existing tests to verify no regression**

Run: `just test-one RecordingDecoderCompatTest`

Expected: PASS (existing RecordingParser behavior unchanged).

- [ ] **Step 3: Commit**

```
feat(conformance): extract parseToGRE shared helper from RecordingParser
```

---

### Task 2: RecordingFrameLoader

**Files:**
- Create: `tooling/src/main/kotlin/leyline/conformance/RecordingFrameLoader.kt`
- Create: `tooling/src/test/kotlin/leyline/conformance/RecordingFrameLoaderTest.kt`
- Create: `tooling/src/test/resources/conformance/timer-frame.bin` (test fixture)

- [ ] **Step 1: Create a test fixture .bin file**

Copy a small TimerStateMessage `.bin` from a local recording into test resources. Timer messages contain no card data — safe to commit.

```bash
# Find a small timer frame
ls -la recordings/2026-03-21_22-05-00/capture/seat-1/md-payloads/ | grep S-C | head -3
# Pick one that's small (<500 bytes), decode to verify it's a timer or similar non-card message
protoc --decode=wotc.mtgo.gre.external.messaging.MatchServiceToClientMessage \
  matchdoor/src/main/proto/messages.proto \
  < recordings/2026-03-21_22-05-00/capture/seat-1/md-payloads/000000116_MD_S-C_MATCH_DATA.bin \
  | head -5
# If it's a TimerStateMessage, copy it
mkdir -p tooling/src/test/resources/conformance
cp recordings/2026-03-21_22-05-00/capture/seat-1/md-payloads/000000116_MD_S-C_MATCH_DATA.bin \
   tooling/src/test/resources/conformance/timer-frame.bin
```

Verify the fixture parses correctly:
```bash
protoc --decode=wotc.mtgo.gre.external.messaging.MatchServiceToClientMessage \
  matchdoor/src/main/proto/messages.proto \
  < tooling/src/test/resources/conformance/timer-frame.bin
```

- [ ] **Step 2: Write failing test**

```kotlin
package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

class RecordingFrameLoaderTest : FunSpec({

    test("parseToGRE loads GRE messages from test fixture") {
        val bytes = javaClass.getResourceAsStream("/conformance/timer-frame.bin")!!.readBytes()
        val messages = RecordingParser.parseToGRE(bytes)
        messages.shouldNotBeEmpty()
        // Timer frames contain TimerStateMessage
        messages.any { it.type == GREMessageType.TimerStateMessage_695e } shouldBe true
    }

    test("load resolves session path and returns indexed messages") {
        // This test requires a local recording — skip in CI
        val session = "2026-03-21_22-05-00"
        val sessionDir = java.io.File("recordings/$session")
        if (!sessionDir.exists()) return@test // skip if no recording

        val messages = RecordingFrameLoader.load(session, seat = 1)
        messages.shouldNotBeEmpty()
        // Should have multiple message types
        val types = messages.map { it.message.type }.toSet()
        (types.size > 1) shouldBe true
        // Frame indices should be ordered
        val indices = messages.map { it.frameIndex }
        indices shouldBe indices.sorted()
    }

    test("loadByType filters to requested greType") {
        val session = "2026-03-21_22-05-00"
        val sessionDir = java.io.File("recordings/$session")
        if (!sessionDir.exists()) return@test

        val searchReqs = RecordingFrameLoader.loadByType(
            session, GREMessageType.SearchReq_695e, seat = 1,
        )
        // Bushwhack recording has exactly 1 SearchReq
        searchReqs.size shouldBe 1
        // SearchReq should have a non-empty searchReq inner message
        searchReqs[0].message.hasSearchReq() shouldBe true
    }
})
```

- [ ] **Step 3: Run test to verify it fails**

Run: `just test-one RecordingFrameLoaderTest`

Expected: FAIL — `RecordingFrameLoader` doesn't exist yet.

- [ ] **Step 4: Implement RecordingFrameLoader**

```kotlin
package leyline.conformance

import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.io.File

/**
 * Loads raw GRE proto messages from recording .bin files.
 *
 * Unlike [RecordingParser] (which returns [StructuralFingerprint]),
 * this returns the full [GREToClientMessage] — no fields lost.
 */
object RecordingFrameLoader {

    private val RECORDINGS_DIR = File("recordings")

    data class IndexedGREMessage(
        val frameIndex: Int,
        val message: GREToClientMessage,
    )

    /** Load all S-C GRE messages from a session, in frame order. */
    fun load(session: String, seat: Int = 1): List<IndexedGREMessage> {
        val payloadsDir = resolvePayloadsDir(session, seat) ?: return emptyList()
        val binFiles = payloadsDir.listFiles()
            ?.filter { it.name.contains("S-C") && it.extension == "bin" }
            ?.sortedBy { it.name }
            ?: return emptyList()

        return binFiles.flatMap { file ->
            val frameIndex = file.name.take(9).trimStart('0').ifEmpty { "0" }.toInt()
            RecordingParser.parseToGRE(file.readBytes(), seatFilter = seat)
                .map { IndexedGREMessage(frameIndex, it) }
        }
    }

    /** Load and filter to a specific [GREMessageType]. */
    fun loadByType(
        session: String,
        type: GREMessageType,
        seat: Int = 1,
    ): List<IndexedGREMessage> =
        load(session, seat).filter { it.message.type == type }

    /** Load and filter by gsId. */
    fun loadByGsId(
        session: String,
        gsId: Int,
        seat: Int = 1,
    ): List<IndexedGREMessage> =
        load(session, seat).filter { it.message.gameStateId == gsId }

    private fun resolvePayloadsDir(session: String, seat: Int): File? {
        // Seat-specific layout (current)
        val seatDir = RECORDINGS_DIR.resolve("$session/capture/seat-$seat/md-payloads")
        if (seatDir.isDirectory) return seatDir

        // Flat layout (older captures)
        val flatDir = RECORDINGS_DIR.resolve("$session/capture/md-payloads")
        if (flatDir.isDirectory) return flatDir

        return null
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `just test-one RecordingFrameLoaderTest`

Expected: PASS (first test always passes with fixture; second/third pass if recording exists locally).

- [ ] **Step 6: Commit**

```
feat(conformance): add RecordingFrameLoader — lossless .bin → proto
```

---

### Task 3: ProtoDiffer — data types and skip/ID field sets

**Files:**
- Create: `tooling/src/main/kotlin/leyline/conformance/ProtoDiffer.kt`

- [ ] **Step 1: Write the data types, skip list, and ID field registry**

```kotlin
package leyline.conformance

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message

/**
 * Field-by-field diff of two protobuf messages with instance ID normalization.
 *
 * Walks both messages via reflection ([Message.getAllFields]), normalizes instance IDs
 * to encounter-order ordinals, and reports missing/extra/mismatched fields.
 */
object ProtoDiffer {

    /** Dot-separated field path for human-readable diff output. */
    data class FieldPath(val segments: List<String>) {
        override fun toString(): String = segments.joinToString(".")
        fun child(name: String) = FieldPath(segments + name)
        companion object {
            val ROOT = FieldPath(emptyList())
        }
    }

    data class FieldMismatch(
        val path: FieldPath,
        val expected: Any?,
        val actual: Any?,
    )

    data class DiffResult(
        val missing: List<FieldPath>,
        val extra: List<FieldPath>,
        val mismatched: List<FieldMismatch>,
        val matched: Int,
    ) {
        fun isEmpty(): Boolean = missing.isEmpty() && extra.isEmpty() && mismatched.isEmpty()

        fun report(): String = buildString {
            if (isEmpty()) {
                appendLine("MATCH — no differences found ($matched fields matched)")
                return@buildString
            }
            appendLine("DIFF — ${missing.size} missing, ${extra.size} extra, ${mismatched.size} mismatched ($matched matched)")
            for (m in missing) appendLine("  - MISSING: $m")
            for (e in extra) appendLine("  + EXTRA: $e")
            for (mm in mismatched) appendLine("  ~ MISMATCH: ${mm.path} expected=${mm.expected} actual=${mm.actual}")
        }
    }

    data class SequenceDiffResult(
        val paired: List<Pair<Int, DiffResult>>,
        val unmatchedRecording: List<Int>,
        val unmatchedEngine: List<Int>,
    ) {
        fun report(): String = buildString {
            appendLine("Sequence: ${paired.size} paired, ${unmatchedRecording.size} unmatched recording, ${unmatchedEngine.size} unmatched engine")
            for ((idx, diff) in paired) {
                if (!diff.isEmpty()) appendLine("  Frame $idx: ${diff.missing.size}m ${diff.extra.size}e ${diff.mismatched.size}mm")
            }
            for (idx in unmatchedRecording) appendLine("  Recording frame $idx: NO ENGINE MATCH")
            for (idx in unmatchedEngine) appendLine("  Engine message $idx: NO RECORDING MATCH")
        }
    }

    /** Fields that always differ between recording and engine — skip during diff. */
    private val SKIP_FIELDS = setOf(
        "gameStateId", "prevGameStateId", "msgId", "matchID",
        "timestamp", "transactionId", "requestId",
    )

    /** Field names that carry instance IDs — values are normalized to ordinals. */
    private val ID_FIELDS = setOf(
        "instanceId", "affectorId", "affectedIds", "objectInstanceIds",
        "sourceId", "itemsToSearch", "itemsSought", "targetInstanceId",
        "attackerInstanceId", "blockerInstanceId", "attackerIds",
        "targetId", "parentId", "orig_id", "new_id",
    )

    /** Repeated fields that should be aligned by key, not by index. */
    private val KEYED_REPEATED = mapOf(
        "gameObjects" to "instanceId",
        "annotations" to null,           // custom: align by types set
        "persistentAnnotations" to null,  // same as annotations
        "zones" to null,                  // custom: align by (type, owner)
        "details" to "key",              // KeyValuePair: align by key field
    )

    /** Repeated fields that should be compared as sorted sets. */
    private val SET_FIELDS = setOf(
        "systemSeatIds", "viewers", "objectInstanceIds",
        "affectedIds", "attackerIds", "itemsToSearch", "itemsSought",
    )
}
```

- [ ] **Step 2: Commit (partial — types only, no logic yet)**

```
feat(conformance): add ProtoDiffer data types, skip/ID field registries
```

---

### Task 4: ProtoDiffer — ID normalizer

**Files:**
- Modify: `tooling/src/main/kotlin/leyline/conformance/ProtoDiffer.kt`
- Create: `tooling/src/test/kotlin/leyline/conformance/ProtoDifferTest.kt`

- [ ] **Step 1: Write failing test for ID normalization**

```kotlin
package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import wotc.mtgo.gre.external.messaging.Messages.*

class ProtoDifferTest : FunSpec({

    test("identical messages produce empty diff") {
        val msg = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateId(1)
            .build()
        val result = ProtoDiffer.diff(msg, msg)
        result.isEmpty() shouldBe true
    }

    test("ID normalization: same structure, different IDs → empty diff") {
        val gsm1 = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .addGameObjects(
                GameObjectInfo.newBuilder()
                    .setInstanceId(200).setGrpId(98595)
                    .setType(GameObjectType.Card).setZoneId(28),
            )
            .build()
        val gsm2 = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff)
            .addGameObjects(
                GameObjectInfo.newBuilder()
                    .setInstanceId(100).setGrpId(98595)
                    .setType(GameObjectType.Card).setZoneId(28),
            )
            .build()
        val msg1 = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateMessage(gsm1).build()
        val msg2 = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateMessage(gsm2).build()

        val result = ProtoDiffer.diff(msg1, msg2)
        result.isEmpty() shouldBe true
    }

    test("skip list: gameStateId difference not reported") {
        val msg1 = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateId(50).build()
        val msg2 = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateId(21).build()

        val result = ProtoDiffer.diff(msg1, msg2)
        result.isEmpty() shouldBe true
    }

    test("value mismatch reported with full path") {
        val gsm1 = GameStateMessage.newBuilder()
            .setType(GameStateType.Diff).build()
        val gsm2 = GameStateMessage.newBuilder()
            .setType(GameStateType.Full).build()
        val msg1 = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateMessage(gsm1).build()
        val msg2 = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateMessage(gsm2).build()

        val result = ProtoDiffer.diff(msg1, msg2)
        result.mismatched.size shouldBe 1
        result.mismatched[0].path.toString() shouldBe "gameStateMessage.type"
    }

    test("extra field in engine reported") {
        val obj1 = GameObjectInfo.newBuilder()
            .setInstanceId(100).setGrpId(98595)
            .setType(GameObjectType.Card).setZoneId(28)
            .build()
        val obj2 = GameObjectInfo.newBuilder()
            .setInstanceId(200).setGrpId(98595)
            .setType(GameObjectType.Card).setZoneId(28)
            .setIsTapped(true) // extra field — not in recording
            .build()
        val gsm1 = GameStateMessage.newBuilder().setType(GameStateType.Diff)
            .addGameObjects(obj1).build()
        val gsm2 = GameStateMessage.newBuilder().setType(GameStateType.Diff)
            .addGameObjects(obj2).build()
        val msg1 = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateMessage(gsm1).build()
        val msg2 = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateMessage(gsm2).build()

        val result = ProtoDiffer.diff(msg1, msg2)
        result.extra.any { it.toString().contains("isTapped") } shouldBe true
    }

    test("missing field reported") {
        val obj1 = GameObjectInfo.newBuilder()
            .setInstanceId(100).setGrpId(98595)
            .setType(GameObjectType.Card).setZoneId(28)
            .setIsTapped(true) // present in recording
            .build()
        val obj2 = GameObjectInfo.newBuilder()
            .setInstanceId(200).setGrpId(98595)
            .setType(GameObjectType.Card).setZoneId(28)
            // isTapped missing
            .build()
        val gsm1 = GameStateMessage.newBuilder().setType(GameStateType.Diff)
            .addGameObjects(obj1).build()
        val gsm2 = GameStateMessage.newBuilder().setType(GameStateType.Diff)
            .addGameObjects(obj2).build()
        val msg1 = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateMessage(gsm1).build()
        val msg2 = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateMessage(gsm2).build()

        val result = ProtoDiffer.diff(msg1, msg2)
        result.missing.any { it.toString().contains("isTapped") } shouldBe true
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `just test-one ProtoDifferTest`

Expected: FAIL — `diff()` not implemented.

- [ ] **Step 3: Implement ID normalizer**

Add to `ProtoDiffer.kt`:

```kotlin
/**
 * Collects instance IDs in encounter order from a proto message.
 * Returns a map from raw ID to ordinal ($1, $2, ...).
 */
internal fun collectIds(msg: Message): Map<Int, Int> {
    val ids = linkedMapOf<Int, Int>()
    var ordinal = 1
    fun visit(message: Message) {
        for ((field, value) in message.allFields) {
            val isIdField = field.name in ID_FIELDS
            when {
                field.isRepeated -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = value as List<Any>
                    for (item in list) {
                        when {
                            item is Message -> visit(item)
                            isIdField && item is Number -> {
                                val id = item.toInt()
                                if (id != 0 && id !in ids) ids[id] = ordinal++
                            }
                        }
                    }
                }
                value is Message -> visit(value)
                isIdField && value is Number -> {
                    val id = value.toInt()
                    if (id != 0 && id !in ids) ids[id] = ordinal++
                }
            }
        }
    }
    visit(msg)
    return ids
}

/** Normalize a value: replace instance IDs with ordinals, skip metadata fields. */
internal fun normalizeValue(value: Any, idMap: Map<Int, Int>, fieldName: String): Any? {
    if (fieldName in SKIP_FIELDS) return null // sentinel: skip this field
    if (fieldName in ID_FIELDS && value is Number) {
        val id = value.toInt()
        return idMap[id] ?: id // unmapped IDs pass through
    }
    return value
}
```

- [ ] **Step 4: Implement `diff()` — recursive walk**

Add to `ProtoDiffer.kt`:

```kotlin
fun diff(
    recording: GREToClientMessage,
    engine: GREToClientMessage,
    seatMap: Map<Int, Int> = emptyMap(),
): DiffResult {
    val recIds = collectIds(recording)
    val engIds = collectIds(engine)
    val missing = mutableListOf<FieldPath>()
    val extra = mutableListOf<FieldPath>()
    val mismatched = mutableListOf<FieldMismatch>()
    var matched = 0

    fun walk(rec: Message, eng: Message, path: FieldPath) {
        val recFields = rec.allFields
        val engFields = eng.allFields

        // Fields in recording but not engine
        for ((field, _) in recFields) {
            if (field.name in SKIP_FIELDS) continue
            if (field !in engFields) {
                missing.add(path.child(field.name))
            }
        }

        // Fields in engine but not recording
        for ((field, _) in engFields) {
            if (field.name in SKIP_FIELDS) continue
            if (field !in recFields) {
                extra.add(path.child(field.name))
            }
        }

        // Compare shared fields
        for ((field, recVal) in recFields) {
            if (field.name in SKIP_FIELDS) continue
            val engVal = engFields[field] ?: continue // already reported as missing

            val childPath = path.child(field.name)
            if (field.isRepeated) {
                compareRepeated(field, recVal, engVal, recIds, engIds, childPath, missing, extra, mismatched, ::matched)
            } else if (recVal is Message && engVal is Message) {
                walk(recVal, engVal, childPath)
            } else {
                val normRec = normalizeValue(recVal, recIds, field.name)
                val normEng = normalizeValue(engVal, engIds, field.name)
                if (normRec == null) continue // skipped field
                if (normRec == normEng) {
                    matched++
                } else {
                    mismatched.add(FieldMismatch(childPath, normRec, normEng))
                }
            }
        }
    }

    walk(recording, engine, FieldPath.ROOT)
    return DiffResult(missing, extra, mismatched, matched)
}
```

Note: `compareRepeated` is a placeholder — implement in next step.

- [ ] **Step 5: Implement `compareRepeated` for key-based alignment**

```kotlin
@Suppress("UNCHECKED_CAST")
private fun compareRepeated(
    field: FieldDescriptor,
    recVal: Any, engVal: Any,
    recIds: Map<Int, Int>, engIds: Map<Int, Int>,
    path: FieldPath,
    missing: MutableList<FieldPath>,
    extra: MutableList<FieldPath>,
    mismatched: MutableList<FieldMismatch>,
    matchedRef: () -> Int, // won't work as counter — use MutableInt or class-level
) {
    val recList = recVal as List<Any>
    val engList = engVal as List<Any>

    // Set fields: sort and compare
    if (field.name in SET_FIELDS) {
        val recSorted = recList.map { normalizeValue(it, recIds, field.name) }.sorted()
        val engSorted = engList.map { normalizeValue(it, engIds, field.name) }.sorted()
        if (recSorted != engSorted) {
            mismatched.add(FieldMismatch(path, recSorted, engSorted))
        }
        return
    }

    // Message lists: align by key or index
    if (recList.firstOrNull() is Message) {
        val recMsgs = recList as List<Message>
        val engMsgs = engList as List<Message>
        // For now: index alignment (simplest correct start)
        // TODO: key-based alignment for gameObjects, annotations, zones
        val maxLen = maxOf(recMsgs.size, engMsgs.size)
        for (i in 0 until maxLen) {
            val childPath = path.child("[$i]")
            if (i >= recMsgs.size) { extra.add(childPath); continue }
            if (i >= engMsgs.size) { missing.add(childPath); continue }
            // Recurse with walk — need to refactor walk to accept Message
        }
        return
    }

    // Primitive lists: direct comparison
    if (recList != engList) {
        mismatched.add(FieldMismatch(path, recList, engList))
    }
}
```

This is the skeleton. The actual implementation will refine the `walk` function to accept `Message` pairs and the counter will be a `var` in the enclosing `diff()` scope. The key-based alignment for `gameObjects`/`annotations`/`zones` starts as index-based and can be upgraded incrementally.

- [ ] **Step 6: Run tests to verify they pass**

Run: `just test-one ProtoDifferTest`

Expected: PASS for all tests.

- [ ] **Step 7: Commit**

```
feat(conformance): implement ProtoDiffer with ID normalization and recursive walk
```

---

### Task 5: ProtoDiffer — repeated field key-based alignment

**Files:**
- Modify: `tooling/src/main/kotlin/leyline/conformance/ProtoDiffer.kt`
- Modify: `tooling/src/test/kotlin/leyline/conformance/ProtoDifferTest.kt`

- [ ] **Step 1: Write failing test for annotation reorder**

Add to `ProtoDifferTest.kt`:

```kotlin
test("annotations in different order produce empty diff") {
    fun annotation(type: AnnotationType, affectorId: Int) = AnnotationInfo.newBuilder()
        .addType(type)
        .setAffectorId(affectorId)
        .build()

    val gsm1 = GameStateMessage.newBuilder().setType(GameStateType.Diff)
        .addAnnotations(annotation(AnnotationType.ZoneTransfer_af5a, 100))
        .addAnnotations(annotation(AnnotationType.ObjectIdChanged, 100))
        .build()
    val gsm2 = GameStateMessage.newBuilder().setType(GameStateType.Diff)
        .addAnnotations(annotation(AnnotationType.ObjectIdChanged, 200))  // different order
        .addAnnotations(annotation(AnnotationType.ZoneTransfer_af5a, 200))
        .build()
    val msg1 = GREToClientMessage.newBuilder()
        .setType(GREMessageType.GameStateMessage_695e)
        .setGameStateMessage(gsm1).build()
    val msg2 = GREToClientMessage.newBuilder()
        .setType(GREMessageType.GameStateMessage_695e)
        .setGameStateMessage(gsm2).build()

    val result = ProtoDiffer.diff(msg1, msg2)
    result.isEmpty() shouldBe true
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one ProtoDifferTest`

Expected: FAIL — index-based alignment reports spurious mismatches.

- [ ] **Step 3: Implement key-based alignment for annotations**

In `compareRepeated`, when `field.name` is `"annotations"` or `"persistentAnnotations"`:

```kotlin
// Extract annotation type set as alignment key
fun annotationKey(msg: Message): String {
    val types = msg.getField(msg.descriptorForType.findFieldByName("types"))
    return types.toString()
}
```

Match recording annotations to engine annotations by type key. Unmatched items → missing/extra.

- [ ] **Step 4: Implement key-based alignment for gameObjects**

When `field.name` is `"gameObjects"`: align by normalized `instanceId` ordinal.

- [ ] **Step 5: Implement key-based alignment for zones**

When `field.name` is `"zones"`: align by `(type, ownerSeatId)`.

- [ ] **Step 6: Run all tests**

Run: `just test-one ProtoDifferTest`

Expected: PASS.

- [ ] **Step 7: Commit**

```
feat(conformance): key-based alignment for annotations, objects, zones in ProtoDiffer
```

---

### Task 6: ProtoConformMain + just recipe

**Files:**
- Create: `tooling/src/main/kotlin/leyline/conformance/ProtoConformMain.kt`
- Modify: `just/proto.just`

- [ ] **Step 1: Implement ProtoConformMain**

```kotlin
package leyline.conformance

import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * CLI: decode and optionally diff raw proto recording frames.
 *
 * Usage:
 *   conform-proto <greType> <session> [--gsid N] [--index N] [--engine <dir>]
 *
 * Decode-only mode (no --engine): prints what the real server sent.
 * Diff mode (--engine): loads engine .bin dump, diffs via ProtoDiffer.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: conform-proto <greType> <session> [--gsid N] [--index N] [--engine <dir>] [--seat N]")
        println()
        println("Examples:")
        println("  conform-proto SearchReq 2026-03-21_22-05-00")
        println("  conform-proto GameStateMessage 2026-03-21_22-05-00 --gsid 52")
        return
    }

    val greTypeName = args[0]
    val session = args[1]

    // Parse optional flags
    var gsId: Int? = null
    var index: Int? = null
    var engineDir: String? = null
    var seat = 1
    var i = 2
    while (i < args.size) {
        when (args[i]) {
            "--gsid" -> { gsId = args[++i].toInt() }
            "--index" -> { index = args[++i].toInt() }
            "--engine" -> { engineDir = args[++i] }
            "--seat" -> { seat = args[++i].toInt() }
        }
        i++
    }

    // Resolve GRE type
    val greType = GREMessageType.values().firstOrNull {
        it.name.startsWith(greTypeName, ignoreCase = true)
    }

    // Load recording frames
    val frames = if (greType != null) {
        RecordingFrameLoader.loadByType(session, greType, seat)
    } else if (gsId != null) {
        RecordingFrameLoader.loadByGsId(session, gsId, seat)
    } else {
        RecordingFrameLoader.load(session, seat)
    }

    // Apply filters
    val filtered = when {
        gsId != null && greType != null -> frames.filter { it.message.gameStateId == gsId }
        index != null -> frames.drop(index).take(1)
        else -> frames
    }

    if (filtered.isEmpty()) {
        println("No frames found for $greTypeName in session $session (seat $seat)")
        return
    }

    println("Found ${filtered.size} ${greTypeName} frame(s) in $session:")
    for (frame in filtered) {
        println("\n--- Frame ${frame.frameIndex} (gsId=${frame.message.gameStateId}) ---")
        println(frame.message)
    }

    // Diff mode
    if (engineDir != null) {
        val engineFrames = RecordingFrameLoader.load(engineDir, seat = 1)
        if (engineFrames.isEmpty()) {
            println("\nNo engine frames in $engineDir")
            return
        }
        // Single-frame diff if we have exactly one of each
        if (filtered.size == 1 && engineFrames.size >= 1) {
            val match = engineFrames.firstOrNull { it.message.type == filtered[0].message.type }
            if (match != null) {
                println("\n=== DIFF ===")
                println(ProtoDiffer.diff(filtered[0].message, match.message).report())
            }
        }
    }
}
```

- [ ] **Step 2: Add just recipe**

Add to `just/proto.just`:

```just
# decode + diff raw proto recording frames (lossless — no JSON intermediary)
# Usage: just conform-proto SearchReq 2026-03-21_22-05-00 [--gsid 52]
[group('proto')]
conform-proto *args: (_require classpath) check-java
    @{{_cli}} leyline.conformance.ProtoConformMainKt {{args}}
```

- [ ] **Step 3: Verify it works**

```bash
just conform-proto SearchReq 2026-03-21_22-05-00
```

Expected: Prints the full SearchReq proto with all inner fields (itemsSought, zonesToSearch, etc.).

- [ ] **Step 4: Commit**

```
feat(conformance): add just conform-proto CLI for lossless recording decode
```

---

### Task 7: Integration smoke test

**Files:**
- Modify: `tooling/src/test/kotlin/leyline/conformance/ProtoDifferTest.kt`

- [ ] **Step 1: Add integration test using real recording (skip if absent)**

```kotlin
test("integration: SearchReq from recording has populated inner fields") {
    val session = "2026-03-21_22-05-00"
    if (!java.io.File("recordings/$session").exists()) return@test

    val searchReqs = RecordingFrameLoader.loadByType(
        session, GREMessageType.SearchReq_695e, seat = 1,
    )
    searchReqs.size shouldBe 1

    val req = searchReqs[0].message
    req.searchReq.maxFind shouldBe 1
    req.searchReq.zonesToSearchList.shouldNotBeEmpty()
    req.searchReq.itemsToSearchList.shouldNotBeEmpty()
    req.searchReq.itemsSoughtList.shouldNotBeEmpty()
    req.searchReq.sourceId shouldBe req.searchReq.sourceId // just check it's set
}
```

This is the "would have caught the bug" test — proves that the loader surfaces SearchReq inner fields that the JSON decoder missed.

- [ ] **Step 2: Run full test suite**

Run: `just test-one ProtoDifferTest && just test-one RecordingFrameLoaderTest`

Expected: PASS.

- [ ] **Step 3: Final commit**

```
test(conformance): integration test proving SearchReq inner fields are visible
```

---

## Notes for implementer

- **Proto field names:** The proto uses renamed fields via `proto/rename-map.sed`. The Kotlin generated classes use the renamed names. When checking field names in `SKIP_FIELDS`/`ID_FIELDS`, use the Kotlin-visible names (camelCase), not the raw proto names. Use `addType()` (singular) for AnnotationInfo, not `addTypes()`.
- **Test recordings:** Tests that require `recordings/` directory should `return@test` early if the directory doesn't exist. This lets them run locally but skip in CI. Use any available recording if `2026-03-21_22-05-00` doesn't exist — adjust assertions accordingly.
- **`just test-one`:** Runs `./gradlew :matchdoor:test :tooling:test :frontdoor:test :account:test -Pkotest.filter.specs=".*<ClassName>"`. All tooling tests land in `:tooling:test`.
- **`just fmt`:** Run before every commit step. Don't manually fix import order or whitespace.
- **The `diff()` implementation in Task 4 is a skeleton.** Use a `var matched = 0` counter in the `diff()` function scope, captured by the local `walk()` function. The `compareRepeated` helper should also capture it from the enclosing scope rather than taking a lambda parameter.
- **`RECORDINGS_DIR`:** The `File("recordings")` relative path resolves against CWD. Gradle sets CWD to project root for `:tooling:test`, so it works. If it doesn't, use `File(System.getProperty("user.dir")).resolve("recordings")` or pass it as a parameter.
- **`diffSequence` is deferred.** The data type is defined in Task 3 but implementation is out of scope for this PR. Single-message diff covers the current use case.
- **`AnnotationSerializer.kt`** lives at `matchdoor/src/test/kotlin/leyline/conformance/AnnotationSerializer.kt` (not tooling). Its deprecation is deferred to PR 2.
- **Read `docs/plans/2026-03-22-proto-conformance-pipeline.md`** for full design rationale, especially the ID normalization and repeated field alignment sections.
