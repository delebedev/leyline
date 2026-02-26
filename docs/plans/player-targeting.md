# Player Targeting: SelectTargetsReq Protocol Gap Analysis

> Status: **In progress** — branch `feat/player-targeting`
> Recording: `recordings/2026-02-25_22-54-26/capture/payloads/` (proxy session)
> Recording contents: (1) bolt opponent, (2) bolt self (shows UI confirm dialog), (3) creature sac + deal 1 to any target

## Problem

Creature targeting works. Player (face) targeting doesn't — the client shows
no clickable player avatar because `SelectTargetsReq` never includes player
targets and several metadata fields are missing.

## Recording Analysis

Six `SelectTargetsReq` messages in the proxy session. Key files:

| File | Scenario | Targets |
|------|----------|---------|
| `000000319` | Bolt cast → choose target | `id:1` (Cold), `id:2` (Hot) — **players only** |
| `000000323` | After selecting opponent | `id:2` (Unselect), `selectedTargets:1` — confirmation |
| `000000361` | Second bolt → choose | `id:1` (Cold), `id:2` (Hot) — players only |
| `000000372` | After selecting self | `id:1` (Unselect), `selectedTargets:1` — confirm bolt own face |
| `000000400` | Sac creature ability | `id:1` (Cold), `id:2` (Hot), `id:290` (Cold) — **mixed player+creature** |
| `000000410` | After selecting opponent | `id:2` (Unselect), `selectedTargets:1` |

### Key discovery: players = instanceId 1 and 2

Players use their **seatId as instanceId**. No special `TargetType` enum, no sentinel
range — they appear in the same `repeated Target targets` list as creatures.

### SelectTargetsReq fields from recording (reference proto)

```protobuf
// GREToClientMessage wrapper (fields on the wrapper, not inside SelectTargetsReq):
AllowCancel allowCancel = 50;   // Recording: Abort (always)
bool allowUndo = 56;            // Recording: true (always)

message SelectTargetsReq {
  repeated TargetSelection targets = 1;
  uint32 sourceId = 2;           // spell instanceId (e.g. 280)
  uint32 abilityGrpId = 3;       // e.g. 89059
}

message TargetSelection {
  uint32 targetIdx = 1;          // always 1
  repeated Target targets = 2;
  uint32 minTargets = 3;         // 1
  uint32 maxTargets = 4;         // 1
  uint32 selectedTargets = 5;    // count after selection (confirmation round)
  Prompt prompt = 6;             // { promptId: 11869, parameters: { CardId: <sourceInstanceId> } }
  uint32 targetingAbilityGrpId = 8; // same as outer abilityGrpId
  uint32 targetingPlayer = 9;    // seatId of chooser
}

message Target {
  uint32 targetInstanceId = 1;   // player seatId (1/2) or card instanceId
  SelectAction legalAction = 2;  // Select_a1ad or Unselect
  HighlightType highlight = 3;   // Hot (suggested), Cold (other)
}
```

### Highlight semantics

```
enum HighlightType {
  None_ad60 = 0;
  Cold = 1;          // legal but not suggested (self, non-optimal)
  Tepid = 2;         // unused in recording
  Hot = 3;           // suggested target (opponent)
  Counterspell = 4;  // presumably for counter-targeting
  Random = 5;
  CopySpell = 6;
  ReplaceRole = 7;
}
```

Recording pattern: **opponent = Hot, everything else = Cold**. This drives the
client's visual hint (glow/highlight on the avatar or card).

### AllowCancel / AllowUndo

```
enum AllowCancel {
  None_a526 = 0;
  Continue = 1;
  Abort = 2;    // ← recording always uses this
  No_a526 = 3;
}
```

- `allowCancel: Abort` — client shows "Cancel" button, player can back out of targeting.
- `allowUndo: true` — client allows undoing target selection.

Both are fields on `GREToClientMessage` (the wrapper), not on `SelectTargetsReq` itself.

### Selection confirmation flow (two-phase)

1. **Initial**: all targets listed with `legalAction: Select_a1ad` + highlights
2. **After click**: server re-sends with selected target as `Unselect`, `selectedTargets: N`
3. Client can click `Unselect` to change, or confirm to submit `SelectTargetsResp`

We can skip the confirmation round initially (auto-confirm on first selection).

## Current Code Gaps

| What | Where | Gap |
|------|-------|-----|
| Player targets not in candidates | `WebPlayerController.selectTargetsInteractively` | `validTargets` is `List<Card>` — players excluded. `getAllCandidates()` includes players but only used as fallback when card list is empty |
| instanceId lookup assumes cards | `StateMapper.buildSelectTargetsReq` line 672 | `getOrAllocInstanceId(entityId)` — player entityId not in card registry |
| Reverse lookup assumes cards | `TargetingHandler.onSelectTargets` line 38 | `getForgeCardId(instanceId)` — returns null for instanceId 1/2 |
| `allowCancel` not set | `BundleBuilder.selectTargetsBundle` line 421 | Defaults to `None_a526` — client has no cancel button |
| `allowUndo` not set | Same | Defaults to `false` |
| `highlight` not set | `StateMapper.buildSelectTargetsReq` line 674 | All targets get `None` highlight — no visual hint |
| Wrong promptId | `BundleBuilder` line 423 | Uses `DISTRIBUTE_DAMAGE` — should be `11869` for target selection |
| `sourceId` / `abilityGrpId` not set | `StateMapper.buildSelectTargetsReq` | Missing spell metadata |
| `targetIdx` / `targetingAbilityGrpId` / `targetingPlayer` not set | Same | Missing per-selection metadata |

## Implementation Plan

See branch `feat/player-targeting` for the actual implementation.
