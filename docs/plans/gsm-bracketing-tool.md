# GSM Bracketing Extraction Tool

Spec for `scry sequences` — a scry-ts command that extracts canonical GSM bracketing patterns from real server recordings.

## Problem

We collapse multi-GSM interactions into fewer, coarser GSMs. To fix this, we need a precise spec of what the real server emits for each interaction type: how many GSMs, what annotations in each, what GRE requests follow, what updateType, gsId gaps (echoes/priority passes).

This data exists in saved Player.log games (~38 in `~/.scry/games/`). Extracting it manually is slow and error-prone. The tool automates pattern extraction across all saved games.

## What it produces

For each interaction type (targeted spell, untargeted spell, land play, combat damage, ETB trigger, etc.), a canonical GSM sequence:

```
TARGETED_SPELL (14 instances across 8 games):
  Canonical:  CAST_TARGETED → ECHO → TARGETS_CONFIRMED → ECHO → RESOLVE → ECHO
  Variants:   2/14 have RESOLVE_KILL instead of RESOLVE

  Slot 1 — CAST_TARGETED:
    annotations:  [ObjectIdChanged, ZoneTransfer(CastSpell), PlayerSelectingTargets]
    updateType:   Send (14/14)
    followed_by:  SelectTargetsReq (14/14)
    prevGsId:     gsId - 1 (14/14)

  Slot 2 — ECHO:
    annotations:  []
    updateType:   Send (14/14)

  Slot 3 — TARGETS_CONFIRMED:
    annotations:  [PlayerSubmittedTargets, mana_bracket, UserActionTaken]
    updateType:   SendHiFi (14/14)
    followed_by:  ActionsAvailableReq (12/14), none (2/14)

  Slot 4 — ECHO:
    annotations:  []
    updateType:   SendHiFi (14/14)

  Slot 5 — RESOLVE:
    annotations:  [ResStart, DamageDealt?, ModifiedLife?, ResComplete, OIC, ZT(Resolve)]
    updateType:   SendHiFi (14/14)
    followed_by:  ActionsAvailableReq (14/14)

  Slot 6 — ECHO:
    annotations:  []
    updateType:   SendHiFi (14/14)
```

## Architecture

### Step 0: Capture non-GSM GRE messages

**Current gap:** `detectGames()` in `games.ts` only stores GSMs (calls `extractGsm`, discards other message types). Non-GSM GRE messages — `ActionsAvailableReq`, `SelectTargetsReq`, `DeclareAttackersReq`, `PromptReq`, etc. — are dropped.

These are essential: they tell us what request follows a GSM (the `followed_by` field in the output). They also carry `gameStateId` which links them to their paired GSM.

**Fix:** Add a `GreMessageSummary` type alongside `GsmSummary`:

```ts
export interface GreMessageSummary {
  type: string;       // "ActionsAvailableReq", "SelectTargetsReq", etc.
  msgId: number;
  gameStateId: number;
  timestamp: string | null;
}
```

Store both GSMs and non-GSM GRE messages in `Game`, in arrival order:

```ts
export interface Game {
  // ... existing fields ...
  greMessages: GsmSummary[];        // keep for backward compat
  greStream: (GsmSummary | GreMessageSummary)[];  // ordered, all types
}
```

`greStream` preserves the full message sequence. Existing `greMessages` stays as-is — all current commands keep working.

### Step 1: Classify each GSM by role

A GSM's role is determined by its annotation signature. Small fixed enum:

```ts
type GsmRole =
  | "CAST"                  // ZoneTransfer(CastSpell), no PlayerSelectingTargets
  | "CAST_TARGETED"         // ZoneTransfer(CastSpell) + PlayerSelectingTargets
  | "TARGETS_CONFIRMED"     // PlayerSubmittedTargets
  | "RESOLVE"               // ResolutionStart + ResolutionComplete, no ZT(SBA/Destroy)
  | "RESOLVE_KILL"          // ResolutionStart + ResolutionComplete + ZT(SBA_Damage|Destroy)
  | "COMBAT_DAMAGE"         // DamageDealt in CombatDamage/FirstStrikeDamage phase, no ZT death
  | "COMBAT_DAMAGE_KILL"    // DamageDealt + ZT(SBA_Damage) in same GSM
  | "LAND"                  // ZoneTransfer(PlayLand)
  | "DRAW"                  // ZoneTransfer(Draw) — phase=Draw or annotation-only
  | "PHASE"                 // PhaseOrStepModified only (no other transient annotations)
  | "TRIGGER_ENTER"         // AbilityInstanceCreated not part of a mana bracket
  | "TRIGGER_RESOLVE"       // ResolutionStart + AbilityInstanceDeleted (trigger lifecycle end)
  | "MANA_BRACKET"          // ManaPaid present (mana ability lifecycle within a cast)
  | "ECHO"                  // zero transient annotations
  | "UNKNOWN"               // doesn't match any pattern
```

Classification logic (order matters — first match wins):

1. Zero transient annotations → `ECHO`
2. Has `ZoneTransfer` with category `CastSpell` + has `PlayerSelectingTargets` → `CAST_TARGETED`
3. Has `ZoneTransfer` with category `CastSpell`, no `PlayerSelectingTargets` → `CAST`
4. Has `PlayerSubmittedTargets` → `TARGETS_CONFIRMED`
5. Has `ResolutionStart` + `ResolutionComplete` + `ZoneTransfer` with category `SBA_Damage` or `Destroy` → `RESOLVE_KILL`
6. Has `ResolutionStart` + `ResolutionComplete` → `RESOLVE`
7. Has `DamageDealt` in CombatDamage/FirstStrikeDamage phase + `ZoneTransfer(SBA_Damage)` → `COMBAT_DAMAGE_KILL`
8. Has `DamageDealt` in CombatDamage/FirstStrikeDamage phase → `COMBAT_DAMAGE`
9. Has `ZoneTransfer` with category `PlayLand` → `LAND`
10. Has `ZoneTransfer` with category `Draw` → `DRAW`
11. Has `AbilityInstanceCreated` without `ManaPaid` → `TRIGGER_ENTER`
12. Has `ManaPaid` → `MANA_BRACKET`
13. Has only `PhaseOrStepModified` → `PHASE`
14. Otherwise → `UNKNOWN`

**How to get ZoneTransfer category:** The annotation's `details` array contains a key like `category` or the zone_src/zone_dest pair implies it. Check what Player.log JSON actually contains for ZoneTransfer details — the `zone_src`, `zone_dest`, and `category` fields should be in the annotation details. If category isn't explicit, derive from zone pair (Hand→Stack = CastSpell, Library→Hand = Draw, Hand→Battlefield = PlayLand, Stack→Graveyard = Resolve, Battlefield→Graveyard = Destroy/SBA_Damage/Sacrifice).

**Mana bracket detection:** `ManaPaid` + `AbilityInstanceCreated` + `AbilityInstanceDeleted` in the same GSM. The `MANA_BRACKET` role applies when ManaPaid is present but it's NOT part of a CAST or TARGETS_CONFIRMED GSM. In practice, ManaPaid usually co-occurs with CAST or TARGETS_CONFIRMED — those take precedence (rules 2-4 fire first). Standalone MANA_BRACKET is rare (mana ability without casting).

### Step 2: Identify interaction instances

An "interaction" is a card lifecycle that spans multiple GSMs. Anchor on the initiating GSM, walk forward to find the completing GSM. Link by `instanceId` continuity across annotations.

**Interaction types and their boundaries:**

| Type | Start anchor | End anchor | Link key |
|------|-------------|------------|----------|
| `TARGETED_SPELL` | `CAST_TARGETED` | `RESOLVE` or `RESOLVE_KILL` | spell instanceId in ZT(CastSpell) affectedIds, same id in ResolutionStart affectorId |
| `UNTARGETED_SPELL` | `CAST` | `RESOLVE` or `RESOLVE_KILL` | same instanceId link |
| `COMBAT_DAMAGE` | `COMBAT_DAMAGE` or `COMBAT_DAMAGE_KILL` | self (single GSM) | — |
| `LAND_PLAY` | `LAND` | self (single GSM) | — |
| `DRAW` | `DRAW` | self (single GSM) | — |
| `ETB_TRIGGER` | `TRIGGER_ENTER` | `TRIGGER_RESOLVE` | abilityInstanceId in AIC affectedIds, same id in AID affectedIds |

For multi-GSM types, capture ALL GSMs between start and end (inclusive), including echoes and priority passes. This is the full interaction window.

**instanceId extraction from annotations:**

- `ZoneTransfer(CastSpell)`: spell instanceId is in `affectedIds[0]`
- `ResolutionStart`: spell instanceId is in `affectorId`
- `AbilityInstanceCreated`: ability instanceId is in `affectedIds[0]`
- `AbilityInstanceDeleted`: ability instanceId is in `affectedIds[0]`
- `ObjectIdChanged`: `orig_id` and `new_id` in details — follow the chain

When `ObjectIdChanged` remaps an id (e.g., spell 226→285), the tool must track both — the start anchor uses one id, the end anchor may use the remapped id.

### Step 3: Aggregate across games

For each interaction type, collect all instances from all saved games (filter to `source=real,unknown` per scry-ts convention). Group by the role sequence and count.

Output: canonical sequence (most common) + variants with counts.

### Step 4: Per-slot detail

For each slot in a sequence, aggregate field values:

- **annotations:** set of annotation types present, with frequency
- **updateType:** value distribution (Send/SendHiFi/SendAndRecord)
- **followed_by:** next non-GSM GRE message type (from `greStream`), or "none"
- **gsId delta:** gap from previous GSM's gsId (1 = consecutive, >1 = gap)
- **prevGameStateId:** relationship to own gsId (usually gsId - 1)
- **zones_changed:** count of zones in the GSM (0 for echoes)
- **objects_changed:** count of gameObjects in the GSM
- **pAnns_added:** persistent annotation types that appear here but not in prior GSM
- **pAnns_removed:** diffDeletedPersistentAnnotationIds present

## CLI interface

```
scry sequences [--game REF] [--type TYPE] [--json]
```

- Default: all saved games (source=real,unknown), all interaction types
- `--game REF`: single game
- `--type targeted_spell`: filter to one interaction type
- `--json`: machine-readable output (for downstream conformance tooling)

### Human-readable output (default)

```
TARGETED_SPELL  14 instances  8 games
  [1] CAST_TARGETED    Send       → SelectTargetsReq    [OIC ZT(Cast) PST]
  [2] ECHO             Send       —                     []
  [3] TARGETS_CONFIRMED SendHiFi  → AAR                 [PSuT mana UAT]
  [4] ECHO             SendHiFi   —                     []
  [5] RESOLVE          SendHiFi   → AAR                 [RS DD? ML? RC OIC ZT(Res)]
  [6] ECHO             SendHiFi   —                     []
  Variants: 12/14 canonical, 2/14 RESOLVE_KILL at slot 5

COMBAT_DAMAGE  22 instances  12 games
  [1] COMBAT_DAMAGE    SendHiFi   → AAR                 [POS DD SE ML]
  [2] ECHO             SendHiFi   —                     []
  Variants: 18/22 canonical, 4/22 COMBAT_DAMAGE_KILL at slot 1
```

Abbreviations in annotation lists: OIC=ObjectIdChanged, ZT=ZoneTransfer, PST=PlayerSelectingTargets, PSuT=PlayerSubmittedTargets, RS=ResolutionStart, RC=ResolutionComplete, DD=DamageDealt, ML=ModifiedLife, SE=SyntheticEvent, POS=PhaseOrStepModified, UAT=UserActionTaken, AIC=AbilityInstanceCreated, AID=AbilityInstanceDeleted, AAR=ActionsAvailableReq. `?` suffix = present in some instances but not all.

### JSON output (`--json`)

```json
{
  "interactions": [
    {
      "type": "TARGETED_SPELL",
      "instanceCount": 14,
      "gameCount": 8,
      "canonical": ["CAST_TARGETED", "ECHO", "TARGETS_CONFIRMED", "ECHO", "RESOLVE", "ECHO"],
      "variants": [
        { "sequence": ["CAST_TARGETED", "ECHO", "TARGETS_CONFIRMED", "ECHO", "RESOLVE_KILL", "ECHO"], "count": 2 }
      ],
      "slots": [
        {
          "index": 1,
          "role": "CAST_TARGETED",
          "updateType": { "Send": 14 },
          "followedBy": { "SelectTargetsReq": 14 },
          "annotations": {
            "always": ["ObjectIdChanged", "ZoneTransfer", "PlayerSelectingTargets"],
            "sometimes": {}
          },
          "gsIdDelta": { "1": 10, "2": 3, "3": 1 },
          "zonesChanged": { "mean": 2.1, "min": 1, "max": 4 },
          "objectsChanged": { "mean": 1.0, "min": 1, "max": 1 },
          "pAnnsAdded": [],
          "pAnnsRemoved": []
        }
      ]
    }
  ],
  "meta": {
    "gamesScanned": 38,
    "gamesMatched": 12,
    "totalInstances": 87,
    "scryVersion": "0.x.x"
  }
}
```

## What it does NOT do

- Does not compare against leyline output. That's conformance tooling.
- Does not infer `pendingMessageCount` (proto-only field, not in Player.log). That's already documented in message-framing.md and is predictable from sequence structure (GSM followed by request → pending=1).
- Does not generate code or specs. It produces data; humans write the bracketing spec.
- Does not handle PvP seat-2 perspective (yet). Saved games are seat-1 view. Seat-2 patterns (QueuedGSM, SendHiFi for remote actions) come later.

## Implementation notes

### Parser change needed

`games.ts` `detectGames()` currently discards non-GSM GRE messages. Must capture them into `greStream` while keeping `greMessages` for backward compat. Non-GSM messages to capture:

```ts
const INTERESTING_GRE_TYPES = [
  "GREMessageType_ActionsAvailableReq",
  "GREMessageType_SelectTargetsReq",
  "GREMessageType_DeclareAttackersReq",
  "GREMessageType_DeclareBlockersReq",
  "GREMessageType_PromptReq",
  "GREMessageType_MulliganReq",
  "GREMessageType_GroupReq",
  "GREMessageType_SelectNReq",
  "GREMessageType_SearchReq",
  "GREMessageType_IntermissionReq",
  "GREMessageType_QueuedGameStateMessage",
  "GREMessageType_SubmitTargetsResp",
  "GREMessageType_SubmitAttackersResp",
  "GREMessageType_SubmitBlockersResp",
  "GREMessageType_OrderReq",
  "GREMessageType_CastingTimeOptionsReq",
];
```

### ZoneTransfer category extraction

Player.log ZoneTransfer annotations have details like:

```json
{ "key": "zone_src", "valueInt32": [35] },
{ "key": "zone_dest", "valueInt32": [27] },
{ "key": "category", "valueString": ["CastSpell"] }
```

If `category` is missing (some older logs), derive from zone pair using zone type lookup. Zone types are in zone definitions earlier in the GSM or accumulated state.

### File organization

New file: `src/commands/sequences.ts` — the command implementation.
New file: `src/classifier.ts` — GSM role classification logic (shared, testable).

### Testing

Run against a known game where we manually traced the Shock lifecycle (2026-03-30_20-06, gsIds 38-43). The tool should produce:

```
TARGETED_SPELL  1+ instances
  [1] CAST_TARGETED  Send     → ?    [OIC ZT(Cast) PST]
  [2] ECHO           Send     —      []
  [3] TARGETS_CONFIRMED SendHiFi → ?  [PSuT AIC TUP UAT ManaPaid AID]
  [4] ECHO           SendHiFi —      []
  [5] RESOLVE        SendHiFi → ?    [RS DD SE ML RC OIC ZT(Res)]
  [6] ECHO           SendHiFi —      []
```

If it doesn't match, the classifier or interaction boundary detection is wrong.

## Open questions for implementer

1. **ZoneTransfer category field name** — verify the exact key name in Player.log JSON. Could be `category`, `zone_transfer_category`, or something else. Check raw JSON output of a ZoneTransfer annotation: `just scry-ts gsm show <gsId> --json | jq '.annotations[] | select(.type[] | contains("ZoneTransfer"))'`

2. **TRIGGER_ENTER vs mana bracket** — AbilityInstanceCreated appears in both triggered abilities and mana abilities. The difference: mana brackets have ManaPaid in the same GSM. But the classifier handles this by rule ordering (MANA_BRACKET rule checks for ManaPaid, TRIGGER_ENTER checks for AIC without ManaPaid). Verify this holds across recordings.

3. **Multi-target spells** — do they have one TARGETS_CONFIRMED GSM or one per target? Check recordings with multi-target spells if available.

4. **Triggered abilities chaining** — when one trigger triggers another, is that nested TRIGGER_ENTER→TRIGGER_RESOLVE within a parent interaction, or separate interactions? Start simple (flat, separate interactions) and refine if the data shows nesting patterns.

5. **Counter spells** — what does the sequence look like when a spell is countered? The RESOLVE slot probably has different annotations (no effect, just ZT Stack→GY). May need a RESOLVE_COUNTERED role.
