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
- `recordings/<session>/capture/seat-<N>/md-payloads/` for seat-specific frames
- Filter to `*S-C*.bin` files (server-to-client only)
- Parse each as `MatchServiceToClientMessage`, extract `greToClientEvent.greToClientMessagesList`
- Return in filename sort order

Reuses `RecordingParser.parsePayload` parsing logic but returns `GREToClientMessage` instead of `StructuralFingerprint`.

~50 LOC.

### 2. ProtoDiffer

**File:** `tooling/src/main/kotlin/leyline/conformance/ProtoDiffer.kt`

Field-by-field diff of two GRE messages with instance ID normalization.

```kotlin
object ProtoDiffer {
    fun diff(
        recording: GREToClientMessage,
        engine: GREToClientMessage,
        seatMap: Map<Int, Int> = mapOf(2 to 1, 1 to 2),
    ): DiffResult

    fun diffSequence(
        recording: List<GREToClientMessage>,
        engine: List<GREToClientMessage>,
        seatMap: Map<Int, Int> = mapOf(2 to 1, 1 to 2),
    ): SequenceDiffResult
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

Apply `seatMap` to: `ownerSeatId`, `controllerSeatId`, `viewers`, `systemSeatIds`, `seatId` in turn info, zone IDs (offset by seat: P1_HAND=31 vs P2_HAND=35). Zone ID remapping: `zoneId → zoneId ± 4` based on seat swap.

**Skip list:**

These fields always differ and carry no conformance signal:
- `gameStateId`, `prevGameStateId`, `msgId` — counters
- `matchId`, `timestamp`, `transactionId` — session metadata

**Recursive walk:**

Use protobuf reflection (`Message.getAllFields()`) to walk both messages. For repeated fields, align by index. For nested messages, recurse. For primitives, compare after normalization.

~200-250 LOC.

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
4. If `--engine <dir>` specified: load engine output from dump dir, diff
5. If `--puzzle <file>` specified: run puzzle, capture output, diff
6. Otherwise: just decode and print — "what does the real server send?"

The "just decode" mode is immediately useful even before the differ. It replaces `protoc --decode` with something that understands the message structure and prints it readably.

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
  - Seat normalization: seat 1 vs seat 2 → empty diff after remap

### ProtoConformMain
- Integration test (local only, needs recording)
- Manual verification: `just conform-proto SearchReq <session>` prints readable output

## Execution order

1. `RecordingFrameLoader` + tests — foundation, ~50 LOC
2. `ProtoDiffer` + tests — core logic, ~250 LOC
3. `ProtoConformMain` + just recipe — CLI entry point, ~100 LOC
4. Wire into existing `ConformancePipelineTest` — replace JSON intermediary

Total: ~400-500 LOC new code.
