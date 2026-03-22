# Proto-Native Conformance Pipeline

**Date:** 2026-03-22
**Branch:** `feat/library-search` (or new `feat/proto-conformance`)
**Motivation:** SearchReq "No Card to Choose" — root cause invisible because recording decoder drops inner fields. Conformance pipeline built on lossy JSON foundation.

## Problem

The conformance pipeline reads `md-frames.jsonl` (decoded JSON). The decoder (`RecordingDecoder.kt`) is lossy — it drops fields like SearchReq inner fields, `name`, `overlayGrpId`, prompt parameters. Templates extracted from lossy JSON produce lossy specs. The SearchReq bug survived because the wire spec said "all fields empty" — true in the JSON, false in the raw proto.

The pipeline is also split across two runtimes (Kotlin tests write JSON, Python diffs it). No single command.

## Solution

Raw protobuf as the source of truth. Decode `.bin` recordings in Kotlin using the same generated message classes the engine produces. Diff proto objects directly — no intermediate format, no fields lost.

```
Recording (.bin)              Engine (puzzle)
      │                              │
      ▼                              ▼
RecordingFrameLoader           MatchFlowHarness
      │                              │
      ▼                              ▼
List<GREToClientMessage>       List<GREToClientMessage>
      │                              │
      └──────────┬───────────────────┘
                 ▼
            ProtoDiffer
                 │
                 ▼
            DiffResult
```

## Components

### 1. RecordingFrameLoader

**File:** `tooling/src/main/kotlin/leyline/conformance/RecordingFrameLoader.kt`

Reads raw `.bin` files from a recording session, returns full proto objects.

```kotlin
object RecordingFrameLoader {
    /** Load all S-C GRE messages from a session, in frame order. */
    fun load(session: String, seat: Int = 1): List<IndexedGREMessage>

    /** Load and filter to a specific greType. */
    fun loadByType(session: String, type: GREMessageType, seat: Int = 1): List<IndexedGREMessage>

    data class IndexedGREMessage(
        val frameIndex: Int,       // file ordinal (000000NNN)
        val message: GREToClientMessage,
    )
}
```

Resolution logic:
- Try `recordings/<session>/capture/seat-<N>/md-payloads/` first (seat-specific layout)
- Fall back to `recordings/<session>/capture/<mdLabel>/md-payloads/` (flat layout from older captures)
- Filter to `*S-C*.bin` files (server-to-client only)
- Parse each as `MatchServiceToClientMessage`, extract `greToClientEvent.greToClientMessagesList`
- Return in filename sort order

Factor out shared parsing logic from `RecordingParser.parsePayload` into a `parseToGRE()` helper. Both `RecordingParser` (returns fingerprints) and `RecordingFrameLoader` (returns raw GRE messages) call it.

~60 LOC.

**Scope:** Server-to-client messages only (`GREToClientMessage`). Client-to-server messages (`PerformActionResp`, etc.) are out of scope for V1 — the current conformance pain is about what we *send*, not what we *receive*. Future extension.

### 2. ProtoDiffer

**File:** `tooling/src/main/kotlin/leyline/conformance/ProtoDiffer.kt`

Field-by-field diff of two GRE messages with instance ID normalization.

```kotlin
object ProtoDiffer {
    fun diff(
        recording: GREToClientMessage,
        engine: GREToClientMessage,
        seatMap: Map<Int, Int> = emptyMap(),
    ): DiffResult

    fun diffSequence(
        recording: List<GREToClientMessage>,
        engine: List<GREToClientMessage>,
        seatMap: Map<Int, Int> = emptyMap(),
    ): SequenceDiffResult
}

data class SequenceDiffResult(
    val paired: List<Pair<Int, DiffResult>>,  // (recording frame index, diff)
    val unmatchedRecording: List<Int>,          // recording frames with no engine match
    val unmatchedEngine: List<Int>,             // engine messages with no recording match
) {
    fun report(): String
}

data class DiffResult(
    val missing: List<FieldPath>,       // in recording, not in engine
    val extra: List<FieldPath>,         // in engine, not in recording
    val mismatched: List<FieldMismatch>,// both present, values differ
    val matched: Int,                   // fields that matched
) {
    fun report(): String
    fun isEmpty(): Boolean = missing.isEmpty() && extra.isEmpty() && mismatched.isEmpty()
}

data class FieldMismatch(
    val path: FieldPath,
    val expected: Any?,  // recording value (after normalization)
    val actual: Any?,    // engine value (after normalization)
)
```

**ID normalization:**

Both sides have instance IDs that differ (recording used IDs 220-290, engine uses 100-110). Before comparing:

1. Walk each message, collect all integer values at instance-ID-bearing field paths (`instanceId`, `affectorId`, `affectedIds`, `objectInstanceIds`, `sourceId`, `itemsToSearch`, `itemsSought`, `targetInstanceId`, `attackerInstanceId`, etc.)
2. Assign ordinals by encounter order: first ID seen → `$1`, second → `$2`, etc.
3. Compare ordinals, not raw values

**Seat normalization:**

Apply `seatMap` to: `ownerSeatId`, `controllerSeatId`, `viewers`, `systemSeatIds`, `seatId` in turn info.

Zone ID remapping uses an explicit lookup table (not arithmetic):
```
P1_HAND(31) ↔ P2_HAND(35)
P1_LIBRARY(32) ↔ P2_LIBRARY(36)
P1_GRAVEYARD(33) ↔ P2_GRAVEYARD(37)
P1_SIDEBOARD(34) ↔ P2_SIDEBOARD(38)
REVEALED_P1(18) ↔ REVEALED_P2(19)
```
Shared zones (BATTLEFIELD=28, STACK=27, EXILE=29, LIMBO=30, etc.) are unchanged.

**V1 simplification:** Since all current testing is 1vAI where both recording and engine use seat1=human, seat normalization is deferred. The `seatMap` parameter exists in the API but defaults to identity. Add real seat remapping when PvP conformance testing starts.

**Skip list:**

These fields always differ and carry no conformance signal:
- `gameStateId`, `prevGameStateId`, `msgId` — counters
- `matchId`, `timestamp`, `transactionId` — session metadata

**Repeated field alignment:**

Repeated fields fall into two categories:

- **Ordered sequences** (zones list, prompt parameters): align by index.
- **Unordered sets** (annotations, gameObjects, objectInstanceIds): align by key.

Alignment keys per repeated type:
- `gameObjects` → normalized `instanceId` ordinal
- `annotations` / `persistentAnnotations` → `types` set (the annotation type list, e.g. `[ZoneTransfer]`). If multiple annotations share the same type set, break ties by `affectorId` ordinal.
- `zones` → `(zoneType, ownerSeatId)` composite key
- `uniqueAbilities` → positional (ordered)
- `details` (KeyValuePair list) → `key` string
- `systemSeatIds`, `viewers`, `objectInstanceIds` → sort both sides, compare as sets

This is the hardest part of the differ. Get it wrong and every diff is noisy. Start with key-based matching for the known types above; fall back to index alignment for unknown repeated fields.

**Recursive walk:**

Use protobuf reflection (`Message.getAllFields()`) to walk both messages. For nested messages (including `oneof` variants like `gameStateMessage` vs `searchReq`), recurse. For primitives, compare after normalization. Wrong `oneof` case (e.g. recording has `searchReq`, engine has `gameStateMessage`) is reported as a structural mismatch at the message level, not field-level.

~250-300 LOC.

### 3. `just conform-proto` command

**File:** `just/test.just` (recipe) + `tooling/src/main/kotlin/leyline/conformance/ProtoConformMain.kt` (entry point)

```bash
# Diff a specific message type against recording
just conform-proto SearchReq 2026-03-21_22-05-00

# With seat and index options
just conform-proto GameStateMessage 2026-03-21_22-05-00 --gsid 52
just conform-proto CastSpell 2026-03-21_22-05-00 --index 2
```

Steps:
1. `RecordingFrameLoader.loadByType(session, type)` — get recording messages
2. Filter by `--gsid` or `--index` if specified
3. Print recording message summary (greType, gsId, key fields)
4. If `--engine <dir>` specified: load engine `.bin` dump dir, diff via `ProtoDiffer`
5. Otherwise: just decode and print — "what does the real server send?"

The "just decode" mode is immediately useful even before the differ. It replaces `protoc --decode` with something that understands the message structure and prints it readably.

**No `--puzzle` mode in CLI.** Running a puzzle requires matchdoor test infrastructure — pulling that into the tooling CLI would create a `tooling → matchdoor-test` dependency. Instead, puzzle-vs-recording diffing lives in test code (`ConformancePipelineTest`), which already has access to `MatchFlowHarness`. The CLI operates on pre-dumped `.bin` directories only.

~100 LOC for the main class + just recipe.

## What stays unchanged

| Component | Role going forward |
|-----------|-------------------|
| `segments.py` | Interactive exploration — segment listing, ID tracing, quick looks |
| `md-frames.jsonl` | Human-readable session index for browsing |
| `RecordingParser.kt` | Structural fingerprinting for timeline analysis |
| `StructuralDiff.kt` | Shape-level comparison (different abstraction level) |
| `just conform` | Kept alongside — `just conform-proto` runs in parallel, eventually replaces |

## What gets deprecated

| Component | Why |
|-----------|-----|
| `AnnotationSerializer.kt` | No longer needed — proto objects compared directly |
| `ConformancePipelineTest` JSON output | Replaced by direct proto diff in tests |

## Testing

### RecordingFrameLoader
- Unit test: load a known `.bin` from test resources, assert greType, field count
- Test resource: copy one small S-C `.bin` file (a TimerStateMessage or similar small frame) into `tooling/src/test/resources/`
- This IS committable — a single timer message contains no card data

### ProtoDiffer
- Unit tests with hand-built proto objects:
  - Identical messages → empty diff
  - Missing field → reported in `missing`
  - Extra field → reported in `extra`
  - Value mismatch → reported in `mismatched`
  - ID normalization: same structure, different IDs → empty diff
  - Repeated field alignment: annotations in different order → empty diff (matched by type key)
  - Nested mismatch: annotation detail value differs → full field path reported (e.g. `gameStateMessage.annotations[ZoneTransfer].details.category`)
  - Skip list: `gameStateId`/`msgId` differences → not reported
  - Wrong oneof case: recording has `searchReq`, engine has `gameStateMessage` → structural mismatch

### ProtoConformMain
- Smoke test: argument parsing + decode mode doesn't crash on a test `.bin` resource
- Integration test (local only, needs recording): `just conform-proto SearchReq <session>` prints readable output

## Execution order

**PR 1 — new pipeline (purely additive, no existing code modified):**
1. `RecordingFrameLoader` + shared `parseToGRE()` helper + tests — foundation, ~60 LOC
2. `ProtoDiffer` + tests — core logic, ~300 LOC
3. `ProtoConformMain` + just recipe — CLI entry point, ~100 LOC

**PR 2 — wire into tests (modifies existing code, separate review):**
4. Update `ConformancePipelineTest` to use `ProtoDiffer` directly
5. Deprecate `AnnotationSerializer.kt` JSON output path

Total: ~460 LOC new code (PR 1), ~50 LOC changed (PR 2).

### ID normalization: canonical walk order

To ensure encounter-order ID assignment is deterministic, the walker visits fields in proto field-number order (which `getAllFields()` returns by default). Within repeated fields, elements are visited in their natural order. This means both sides must produce objects/annotations in the same structural order for ordinal assignment to align — which is exactly the signal we want to test. If the engine emits annotations in a different order than the recording, ordinals diverge, and the diff catches it.
