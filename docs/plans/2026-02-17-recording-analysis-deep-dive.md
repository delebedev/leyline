# Recording Analysis Deep Dive

**Date:** 2026-02-17
**Branch:** `feat/nexus-quality`
**Module:** `forge-nexus`

## Context

forge-nexus is a compatibility layer that lets the real MTGA (Magic: The Gathering Arena) client connect to Forge's open-source rules engine. We implement the server side of Arena's protobuf protocol — the client connects to us instead of Arena's real servers, and we translate between Forge's game state and Arena's wire format.

We've been chasing visual artifacts for a full day. When a player plays a land or casts a creature, the card either:
- **Duplicates** on the battlefield (two copies of the same card), or
- **Jumps back to hand** after briefly appearing on the battlefield

The root cause is that we don't fully understand the **Diff semantics** — how the real server structures its GameStateMessage Diffs, and how the client's state accumulator processes them. We've been guessing at field combinations (Limbo gameObject vs diffDeletedInstanceIds, when to send each, etc.) and hitting different failure modes with each attempt.

See recent commit history for the oscillation:
```
78994bb fix(nexus): restore Limbo gameObject, remove immediate diffDeletedInstanceIds
835dfec fix(nexus): send diffDeletedInstanceIds for retired instanceIds
09d0319 fix(nexus): don't send Limbo gameObject to owner seat
62e00ef fix(nexus): accumulate Limbo zone across diffs
2983f22 fix(nexus): skip instanceId realloc on Resolve (Stack→Battlefield)
```

We need to stop guessing and build from ground truth.

## Recordings

Two fresh recordings from a real Arena session against Sparky (AI), captured via MITM proxy:

| Recording | Path | Files | Perspective |
|-----------|------|-------|-------------|
| On play | `src/test/resources/recordings/20260217-234330-on-play/` | 54 | Player goes first, plays land T1 |
| On draw | `src/test/resources/recordings/20260217-234330-on-draw-sparky-two-creatures-player-attacked/` | 72 | Sparky goes first, casts 2 creatures, attacks player |

Files are `S-C_MATCH_DATA_*.bin` (server→client) and `C-S_DATA_*.bin` (client→server). Server→client files contain `MatchServiceToClientMessage` protobuf wrapping `GreToClientEvent` with `GREToClientMessage` list. Client→server files contain `ClientToMatchServiceMessage`.

## What We Know

From previous recording analysis (see `docs/recording-analysis-runbook.md`):

1. **instanceId reallocation on zone transfer**: Real server allocates a NEW instanceId when a card changes zones (Hand→BF for land play, Hand→Stack for cast). Exception: Resolve (Stack→BF) keeps the same ID.

2. **Limbo zone**: Protocol-only concept (no engine equivalent). Accumulates retired instanceIds monotonically. The old instanceId goes to Limbo when a card gets a new ID.

3. **ObjectIdChanged annotation**: Carries `orig_id` and `new_id` details (Int32 typed), `affectedIds=[origId]`.

4. **diffDeletedInstanceIds**: GSM field that tells client to remove an object from accumulated state. Real server sends this MUCH LATER (e.g., gs=63 for a zone transfer at gs=10). NOT immediate.

5. **Limbo gameObject**: Real server sends a Private gameObject for the retired ID to the OWNER seat. Opponent doesn't get it.

What we DON'T know (and keep getting wrong):
- **Exact Diff field composition**: Which fields appear in the Diff for a land play? Which zones are included? Which objects? What's the exact set?
- **Client accumulator merge rules**: How does the client combine a Diff into its accumulated state? Does it replace objects by instanceId? Does it use zone objectInstanceIds or gameObject.zoneId for rendering?
- **Interaction between Limbo gameObject and diffDeletedInstanceIds**: We've tried both separately and together — all three combinations produce visual artifacts.

## Milestone 1: Decode & Structure

### 1A: Build a Kotlin decode tool

Write a Kotlin CLI tool that:
1. Reads all `S-C_MATCH_DATA_*.bin` files from a recording directory (sorted by timestamp)
2. Decodes each: `MatchServiceToClientMessage` → `GreToClientEvent` → `GREToClientMessage[]`
3. For each GREToClientMessage, extracts a structured summary
4. Outputs ordered JSONL — one JSON object per GREToClientMessage

**Location:** `src/main/kotlin/forge/nexus/conformance/RecordingDecoder.kt` + CLI main in `RecordingDecoderMain.kt`
**Just target:** `just proto-decode-recording <dir> [output.jsonl]`

Each JSONL line should capture:

```json
{
  "index": 0,
  "file": "S-C_MATCH_DATA_590872644342166.bin",
  "greType": "GameStateMessage",
  "msgId": 5,
  "gsId": 3,
  "gsmType": "Full",
  "updateType": "SendAndRecord",
  "prevGsId": 2,
  "zones": [
    {"zoneId": 28, "type": "Battlefield", "owner": 0, "visibility": "Public", "objectIds": [401, 403]},
    {"zoneId": 31, "type": "Hand", "owner": 1, "visibility": "Private", "objectIds": [100, 101, 102]}
  ],
  "objects": [
    {"instanceId": 401, "grpId": 12345, "zoneId": 28, "type": "Card", "visibility": "Public", "owner": 1, "controller": 1}
  ],
  "annotations": [
    {"id": 50, "types": ["ObjectIdChanged"], "affectorId": 0, "affectedIds": [220], "details": {"orig_id": 220, "new_id": 401}}
  ],
  "persistentAnnotations": [...],
  "diffDeletedInstanceIds": [220],
  "actions": [{"type": "Play", "instanceId": 401}, {"type": "Pass"}],
  "hasActionsAvailableReq": true,
  "hasMulliganReq": false,
  "hasDeclareAttackersReq": false,
  "players": [{"seat": 1, "life": 20}, {"seat": 2, "life": 20}],
  "turnInfo": {"phase": "Main1", "step": "None", "turn": 1, "activePlayer": 1, "priorityPlayer": 1}
}
```

### 1B: Build accumulated state snapshots

Write a second tool (or mode of the same tool) that:
1. Reads the JSONL from step 1A
2. Simulates a client accumulator (Full replaces all state; Diff merges)
3. After each GSM, outputs the full accumulated state

**Accumulator rules** (must match exactly what the MTGA client does — this is what we need to nail down):
- **Full GSM**: Clear objects map + zones map, replace with GSM contents
- **Diff GSM**:
  - Process `diffDeletedInstanceIds` → remove from objects map
  - Merge `gameObjects` → upsert by instanceId into objects map
  - Merge `zones` → replace by zoneId in zones map (only zones present in Diff)
  - Update turnInfo, players if present

Output: JSONL with accumulated state snapshot after each GSM:
```json
{
  "afterGsId": 10,
  "afterIndex": 25,
  "objects": {"401": {"zoneId": 28, "grpId": 12345, ...}, ...},
  "zones": {"28": {"type": "Battlefield", "objectIds": [401, 403]}, ...},
  "invariants": {
    "zoneObjectsMissingFromObjects": [],
    "objectsNotInAnyZone": [220],
    "orphanedZoneRefs": []
  }
}
```

The invariant checks are critical — they tell us where the client's state becomes inconsistent.

### 1C: Action traces

For each recording, identify every zone transfer (land play, creature cast, resolve, combat damage, etc.) and produce a trace showing:
- The gsId where it happened
- GSM type (Full/Diff)
- Exact zones included in the message
- Exact objects included
- Annotations
- diffDeletedInstanceIds
- The accumulated state BEFORE and AFTER

**Output:** `action-traces-on-play.md` and `action-traces-on-draw.md` in `docs/plans/`

### 1D: Diff semantics report

Analyze the traces and write `docs/plans/diff-semantics-report.md`:

1. **For each action type** (land play, creature cast, resolve, combat):
   - Which zones appear in the Diff? (all zones? only changed zones? shared zones always?)
   - Which objects appear? (all objects? only changed? Limbo objects?)
   - diffDeletedInstanceIds: present? what IDs? how many states after the zone transfer?
   - Limbo gameObject: present? what fields (visibility, viewers, zoneId)?

2. **Accumulated state consistency**:
   - After each zone transfer, is the accumulated state internally consistent?
   - Are there moments where a zone references an instanceId that's not in the objects map?
   - How does the client resolve that? (Does it ignore missing refs? Does it show face-down?)

3. **The specific question we can't answer**:
   When a land is played (Hand→BF with instanceId realloc 220→401):
   - Does the Diff include the Hand zone? (with 220 removed from objectInstanceIds)
   - Does the Diff include a Limbo gameObject for 220? What are its exact fields?
   - Does the Diff include diffDeletedInstanceIds=[220]?
   - What does the accumulated objects map look like after processing this Diff?
   - Is object 220 still in the map? With what zoneId?

## Milestone 2: Conformance Tests

Using the structured data from Milestone 1, write tests that verify our implementation produces messages with the same field-level semantics as the real server.

### Approach

NOT golden-file comparison (we already have that). Instead: **semantic assertions** rooted in specific recording observations.

Example: "When a land is played, the Diff GSM at gs=N includes exactly these zones: [Hand, Battlefield, Limbo]. The Limbo zone has objectInstanceIds=[origId]. The objects list includes a gameObject with instanceId=origId, zoneId=Limbo, visibility=Private."

These assertions are parameterized by the game action, not by specific IDs. The test starts a real Forge game, performs the action, and checks that the resulting GSM matches the structural pattern observed in recordings.

### Test location

Extend existing `src/test/kotlin/forge/nexus/conformance/PlayLandFieldTest.kt` and `ClientAccumulator.kt`. Add new test classes as needed.

### What to test

1. **Diff zone inclusion**: After playing a land, which zones appear in the Diff? Assert exact set matches recording.

2. **Diff object inclusion**: After playing a land, which objects appear in the Diff? Assert Limbo gameObject present/absent, correct fields.

3. **Accumulated state after land play**: Feed gameStart + postAction messages through ClientAccumulator. Assert:
   - New instanceId in objects map on BF
   - Old instanceId either in Limbo (with gameObject) or deleted (via diffDeletedInstanceIds)
   - Hand zone doesn't reference old instanceId
   - BF zone references new instanceId
   - No invariant violations

4. **Accumulated state after creature cast+resolve**: Same pattern but for Hand→Stack→BF.

5. **Accumulated state across multiple turns**: Process an entire 2-3 turn sequence. Assert consistency at every step.

### Reference our existing code

- `ClientAccumulator.kt` — current accumulator (may need fixes based on Milestone 1 findings)
- `PlayLandFieldTest.kt` — existing field-level tests
- `ConformanceTestBase.kt` — test helpers (startGameAtMain1, playLand, etc.)
- `StateMapper.kt` — the implementation we're trying to fix (buildFromGame, buildDiffFromGame)
- `BundleBuilder.kt` — constructs message bundles (gameStart, postAction, etc.)
- `GameBridge.kt` — manages instanceId mapping, Limbo tracking, snapshots

## Existing Tools

All available from `forge-nexus/`:

| Tool | Command | Purpose |
|------|---------|---------|
| Proto trace | `just proto-trace <id>` | Trace an instanceId across all payloads in a dir |
| Proto inspect | `just proto-inspect <file>` | Decode and print a single .bin payload |
| Proto compare | `just proto-compare --analyze <file>` | Structured timeline from fingerprints |
| Build | `just build` | Full Maven build (compiles proto + Kotlin) |
| Dev build | `just dev-build` | Fast Kotlin-only compile (~3-5s) |
| Tests | `just test` | All tests |
| Format | `just fmt` | ktlint/spotless |

Proto classes are on the Maven classpath after `just build`. The proto schema is at `src/main/proto/messages.proto`.

`RecordingParser.kt` already parses .bin files into `MatchServiceToClientMessage`. `Trace.kt` already walks proto fields recursively. Reuse these.

## Key Docs

Read these before starting:

| Doc | Why |
|-----|-----|
| `docs/recording-analysis-runbook.md` | Full recording workflow, pattern catalog, known gaps |
| `docs/wire-format.md` | Binary framing (6-byte header + protobuf) |
| `docs/bridge-architecture.md` | How GameBridge/StateMapper/BundleBuilder fit together |
| `CLAUDE.md` | Module overview and build instructions |

## Deliverables

1. **Kotlin decode tool** — committed, with `just` target
2. **JSONL outputs** for both recordings — committed in `src/test/resources/recordings/`
3. **Action traces** — `docs/plans/action-traces-*.md`
4. **Diff semantics report** — `docs/plans/diff-semantics-report.md`
5. **Updated/new conformance tests** — in `src/test/kotlin/forge/nexus/conformance/`
6. **ClientAccumulator fixes** — if Milestone 1 reveals our accumulator logic is wrong

## Success Criteria

After this work, we should be able to answer definitively:
- What exact fields does the real server include in a Diff for a land play?
- What is the correct client accumulator behavior?
- Does the Limbo gameObject prevent the "jump back to hand" artifact?
- Does diffDeletedInstanceIds need to be immediate or deferred?
- What combination of fields produces correct visual behavior?
