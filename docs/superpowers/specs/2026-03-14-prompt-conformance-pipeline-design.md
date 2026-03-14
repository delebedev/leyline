# Prompt Conformance Pipeline

**Date:** 2026-03-14
**Status:** Design
**Motivation:** Lossy JSON decoder caused wrong diagnosis — removed fields (`legalDamageRecipients`, `canSubmitAttackers`) the real server actually sends. No tooling exists to validate prompt message shapes against recordings.

## Problem

The conformance pipeline handles zone-transfer segments (PlayLand, CastSpell, Resolve) but has no support for **prompt messages** — the interactive client-facing choice points that drive combat, targeting, and modal choices. These are where the client-visible bugs live (All Attack broken, targeting arrows, modal choices).

The recording decoder (`RecordingDecoder.kt`) uses hand-written summary data classes that allowlist fields. Any field not explicitly included is silently dropped. This is lossy-by-default — the opposite of what a conformance tool needs.

## Hard Constraint: Lossless by Design

**No summary data classes for prompt messages.** Instead:

```
Proto message → protobuf JsonFormat.printer() → full JSON (zero field loss)
```

`JsonFormat.printer()` from `protobuf-java-util` serializes any proto message to JSON with complete field preservation. No data class to maintain, no fields to forget. If the upstream proto schema adds a field, the JSON automatically includes it.

**Dependency:** Requires adding `com.google.protobuf:protobuf-java-util` to `tooling/build.gradle.kts` (and `matchdoor/build.gradle.kts` for engine-side serialization). Version must align with existing `protobuf-java` used for codegen.

**Field naming:** `JsonFormat` outputs proto field names (snake_case: `attacker_instance_id`, `legal_damage_recipients`). Existing summary classes use camelCase. The diff engine and templatizer must use snake_case consistently for prompt data. Existing zone-transfer templates use camelCase — these are separate data paths, no conflict.

This applies to all prompt-related messages:
- `DeclareAttackersReq` / `DeclareAttackersResp`
- `DeclareBlockersReq` / `DeclareBlockersResp`
- `SubmitAttackersReq` / `SubmitAttackersResp`
- `SubmitBlockersReq` / `SubmitBlockersResp`
- `GroupReq` / `GroupResp`

GSM diffs within prompt lifecycles use the existing annotation/object decoder (already sufficiently detailed).

### Convenience overlay

For human readability in `tape segment list` output, add lightweight computed fields alongside the full JSON:
- `attackerCount`, `blockerCount`
- `instanceIds` (flat list for quick scanning)
- `hasAutoDeclare`, `hasSelectedRecipients`

These are **derived from** the full JSON, never the source of truth. The diff engine works on the full proto JSON.

## Scope: v1 Prompt Types

| Type | Messages | Why now |
|------|----------|---------|
| DeclareAttackers | Req/Resp + Submit | Blocking All Attack fix |
| DeclareBlockers | Req/Resp + Submit | Same protocol pattern as attackers |
| GroupReq | Req/Resp | Scry, surveil, modal — high-frequency prompts |

SelectTargets, MulliganReq, CastingTimeOptions deferred to v2.

## Architecture

### 1. RecordingDecoder Changes

**Current:** `decodeGRE()` dispatches on message type, builds a `DecodedMessage` with hand-picked fields. Prompt messages get lossy summaries.

**New:** For prompt messages, use `JsonFormat.printer()` to serialize the inner request/response proto to a JSON string, then embed it as a structured JSON field in `DecodedMessage`.

```kotlin
// Current (lossy)
if (gre.hasDeclareAttackersReq()) {
    val req = gre.declareAttackersReq
    declareAttackers = DeclareAttackersSummary(
        attackers = req.attackersList.map { AttackerSummary(it.attackerInstanceId, it.mustAttack) },
        // legalDamageRecipients: DROPPED
        // selectedDamageRecipient: DROPPED
    )
}

// New (lossless)
if (gre.hasDeclareAttackersReq()) {
    promptData = JsonFormat.printer()
        .omittingInsignificantWhitespace()
        .print(gre.declareAttackersReq)
    // Full proto JSON — every field preserved
}
```

**`DecodedMessage` additions:**
```kotlin
@Serializable
data class DecodedMessage(
    // ... existing fields ...
    val promptType: String? = null,        // "DeclareAttackersReq", "GroupReq", etc.
    val promptData: JsonElement? = null,   // Full proto JSON (lossless)
)
```

`promptType` is the proto message type name (stripped of suffixes). Set **only** on prompt-carrying messages — not on GSMs or other non-prompt messages. `segments.py` uses `promptType` presence as the discovery signal for prompt lifecycles. `promptData` is the full proto serialized via `JsonFormat`. Both present only for prompt messages.

**Backward compatibility:** Existing summary fields (`declareAttackers`, `clientAttackers`, `groupReq`, etc.) remain for now — no breakage. `promptData` is additive. Once the prompt pipeline is validated, the old summaries can be deprecated.

**Client→Server messages** (DeclareAttackersResp, SubmitAttackersReq, GroupResp) get the same treatment — `promptData` contains the full client proto JSON.

### 2. Prompt Lifecycle Segments

`segments.py` gains a new discovery path alongside zone-transfer categories.

**Discovery:** Scan frames for `greType` matching prompt request types:
```python
PROMPT_TYPES = {
    'DeclareAttackersReq': 'DeclareAttackers',
    'DeclareBlockersReq': 'DeclareBlockers',
    'GroupReq': 'Group',
}
```

**Lifecycle extraction:** From each initial request, walk forward collecting the full interaction:

```python
@dataclass
class PromptLifecycle:
    category: str                    # "DeclareAttackers", "DeclareBlockers", "Group"
    initial_req: dict                # First prompt (full proto JSON)
    initial_gsms: list[dict]         # GSMs preceding/accompanying initial req
                                     # (combat setup diffs between phase transition and prompt)
    rounds: list[PromptRound]        # 0-N echo rounds
    submit: dict | None              # SubmitAttackersReq/SubmitBlockersReq (combat only)
    submit_resp: dict | None         # SubmitAttackersResp/SubmitBlockersResp
    result_gsm: dict | None          # Post-submit GSM

@dataclass
class PromptRound:
    response: dict                   # Client response (DeclareAttackersResp, GroupResp)
    echo_gsms: list[dict]            # Server echo GSM diffs (may be multiple —
                                     # recordings show 1-3 GSMs between response and re-prompt)
    echo_req: dict | None            # Server re-prompt (DeclareAttackersReq again)
```

**Why lists for GSMs:** Recordings show that multiple GSMs can appear between protocol events. At idx 3516-3519 in recording `2026-03-06_22-37-41`, 3 GSM diffs (SendHiFi + SendAndRecord) arrive between the phase transition and the first DeclareBlockersReq. Similarly, after a DeclareAttackersResp toggle, 1-2 GSMs may precede the re-prompt. The lifecycle model must capture all of them.

**Boundary detection:** Lifecycle ends at:
- `SubmitAttackersReq` / `SubmitBlockersReq` (combat prompts)
- `GroupResp` (group prompts — single round, no submit)
- Next phase-transition GSM (fallback — prompt was cancelled or timed out)
- Next prompt of a different type (new interaction started)

**GroupReq specifics:** GroupReq is simpler — single Req → Resp, no iterative toggles, no Submit. The lifecycle is:
```
GroupReq → GroupResp → result GSM
```
Still extracted as a `PromptLifecycle` with zero rounds and no submit.

### 3. Prompt Templatization

Same `$var_N` slot system as zone-transfer templates. Instance IDs in prompt fields get symbolic slots.

**What gets templatized:**
- `attackerInstanceId` in attacker lists
- `blockerInstanceId` and `selectedAttackerInstanceIds` in blocker lists
- `instanceIds` in GroupReq `groupSpecs`
- `sourceId` in GroupReq
- `instanceId` in echo GSM objects
- `affectorId` / `affectedIds` in echo GSM annotations

**What stays literal:**
- `playerSystemSeatId` in DamageRecipient (structural — seat number)
- `type` in DamageRecipient (Player vs Planeswalker)
- `promptId` (structural — prompt type)
- `canSubmitAttackers`, `hasRequirements`, `hasRestrictions` (booleans)
- `grpId`, `zoneId`, `zoneType`, `isFacedown` in GroupReq specs
- `alternativeGrpId` on Attacker (card identity, not instance)
- `attackWarnings`, `blockWarnings` (structural)

### 4. Prompt Diff Engine

Compares engine prompt lifecycle against recording prompt lifecycle after templatization and ID binding.

**Diff checks per lifecycle:**

| Check | Description | Example failure |
|-------|-------------|-----------------|
| `initial.fields` | All fields on initial Req present with correct values | Missing `legalDamageRecipients` |
| `initial.attacker_shape` | Per-attacker field set matches | Missing `selectedDamageRecipient` (should be absent initially) |
| `echo.delta` | Fields that change between initial and echo Req | `selectedDamageRecipient` not set after toggle |
| `echo.attacker_list` | Which attackers in echo `attackers` vs `qualifiedAttackers` | Issue #64: re-prompt sends full list instead of committed |
| `echo.gsm.objects` | Object fields in echo GSM (attackState, blockState) | `attackState` set when recording shows None |
| `echo.gsm.update` | GSM updateType matches | Send instead of SendAndRecord |
| `echo.gsm.pending` | pendingMessageCount matches | Non-zero when recording shows 0 |
| `submit.result` | ResultCode matches | Wrong result code |
| `lifecycle.round_count` | Number of echo rounds (informational) | Not a pass/fail — just logged |

**Output format:** Same as existing `just conform`:
- PASS/FAIL per check
- Gap report listing field-level divergences
- JSON output mode (`--json`) for programmatic consumption
- Golden baseline support (`--golden`) for regression detection

### 5. Engine Test Harness

`ConformancePipelineTest.kt` gains prompt scenarios that exercise the full lifecycle and capture output.

**DeclareAttackers scenario:**
1. Load puzzle with haste creatures (Raging Goblin + Mountains)
2. Pass to combat → receive DeclareAttackersReq
3. Send DeclareAttackersResp (toggle one attacker) → receive echo
4. Send DeclareAttackersResp (autoDeclare=true) → receive echo
5. Send SubmitAttackersReq → receive SubmitAttackersResp + GSM
6. Capture all messages as lifecycle JSON → `build/conformance/declare-attackers-lifecycle.json`

**DeclareBlockers scenario:** Similar, with AI attacking and human blocking.

**GroupReq scenario:** Already partially covered (scry test exists). Extend to capture full lifecycle JSON.

## CLI Interface

```bash
# List all segments including prompts
just tape segment list [session]
# Output:
#   CastSpell: 85 segments
#   PlayLand: 76 segments
#   DeclareAttackers: 12 segments    ← NEW
#   DeclareBlockers: 8 segments      ← NEW
#   Group: 5 segments                ← NEW

# Show a specific prompt lifecycle (full proto fields)
just tape segment show DeclareAttackers [session] [--index N]

# Extract templatized lifecycle
just tape segment template DeclareAttackers [session] [--index N]

# Diff engine output against recording
just tape conform run template.json engine-lifecycle.json [--golden golden.json]

# One-shot recipe
just conform DeclareAttackers [session]
just conform-golden DeclareAttackers [session]
```

## Files

| File | Change | Size |
|------|--------|------|
| `tooling/build.gradle.kts` | Add `protobuf-java-util` dependency | S |
| `matchdoor/build.gradle.kts` | Add `protobuf-java-util` dependency (engine-side serialization) | S |
| `tooling/.../RecordingDecoder.kt` | Add `promptType` + `promptData` (JsonFormat) to DecodedMessage | S |
| `tools/tape/segments.py` | Add prompt lifecycle discovery, extraction, templatization, diff | L |
| `matchdoor/.../conformance/PromptSerializer.kt` | New: serialize engine prompt messages via JsonFormat (companion to AnnotationSerializer) | M |
| `matchdoor/.../ConformancePipelineTest.kt` | Add DeclareAttackers + DeclareBlockers scenarios | M |
| `just/test.just` | Add prompt conform recipes | S |
| Golden baselines | `matchdoor/src/test/resources/golden/conform-declare-attackers.json` | S |

### Re-decode existing recordings

After the RecordingDecoder changes, existing `md-frames.jsonl` files should be re-decoded to include `promptData`. The 2026-03-06_22-37-41 recording's GroupReq decoded as empty in the current JSONL — re-decoding with the JsonFormat path will fix this.

## Validation

The pipeline is validated when:
1. `just tape segment list` shows DeclareAttackers/DeclareBlockers/Group counts matching manual frame inspection
2. `just tape segment show DeclareAttackers` displays ALL proto fields (legalDamageRecipients, selectedDamageRecipient, canSubmitAttackers, manaCost)
3. `just conform DeclareAttackers` produces a field-level diff that unambiguously shows what our server sends vs the recording
4. The diff for the current codebase shows the exact gaps needed to fix All Attack

## What This Enables

After this ships, fixing All Attack becomes:
```bash
just conform DeclareAttackers 2026-03-06_22-37-41
```
→ Exact field-by-field diff. No more guessing from lossy JSON. The recording is the spec, and the tooling enforces it.
