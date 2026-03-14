# SubmitTargets Wire Spec — Shock (grpId=75525)

Recording: `2026-03-10_08-23-48`
Spell: Shock (grpId=75525), instanceId=292 (stack), caster=seat 2, target=player seat 1 (instanceId=1)

---

## Message Sequence Table

| # | idx | dir | greType | msgId | gsId | key fields |
|---|-----|-----|---------|-------|------|------------|
| -3 | 9191 | S→C | GameStateMessage (Diff/SendAndRecord) | 90 | 63 | turnInfo: End step turn 3, priorityPlayer=2 |
| -2 | 9192 | S→C | ActionsAvailableReq | 91 | 63 | actions: Cast 221/75525, ActivateMana 285, Pass, FloatMana |
| -1 | 9193 | C→S | PerformActionResp | — | 63 | Cast instanceId=221 grpId=75525, autoPassPriority=No |
| 0  | 9194 | S→C | GameStateMessage (Diff/Send) | 92 | 64 | Shock on stack: zone 35→27, instanceId 221→292; PlayerSelectingTargets(affectorId=2, affectedIds=[292]); `pendingMessageCount=1` |
| 1  | 9195 | S→C | SelectTargetsReq | 93 | 64 | promptId=10; see promptData below |
| 2  | 9196 | C→S | SelectTargetsResp | — | 64 | targetIdx=1, targetInstanceIds=[1] |
| 3  | 9197 | S→C | GameStateMessage (Diff/Send) | 94 | 65 | actions only diff, no zone/object changes |
| 4  | 9198 | S→C | SelectTargetsReq (re-prompt) | 95 | 65 | same structure, selected target reflected; see re-prompt below |
| 5  | 9199 | C→S | SubmitTargetsReq | — | 65 | type-only, no payload |
| 6  | 9200 | S→C | QueuedGameStateMessage | 92 | 64 | seat 1 perspective catch-up: Shock on stack (same as msg 0) |
| 7  | 9201 | S→C | QueuedGameStateMessage | 94 | 65 | seat 1 perspective catch-up |
| 8  | 9202 | S→C | GameStateMessage (Diff/SendHiFi) | 96 | 66 | PlayerSubmittedTargets(affectorId=2, affectedIds=[292]); mana paid (Mountain tapped); TargetSpec persistent annotation added |
| 9+ | 9203–9213 | S→C | GameStateMessage (Diff/SendHiFi) x3 | 97–98 | 67–68 | gsId=68: Shock resolves — DamageDealt 2 to player 1, ModifiedLife −2, ZoneTransfer 27→37 (Resolve), ObjectIdChanged 292→294 |

---

## SelectTargetsReq (initial) — index 9195, msgId=93, gsId=64

```proto
selectTargetsReq {
  targets {
    targetIdx: 1
    targets {
      targetInstanceId: 1      // player seat 1
      legalAction: Select_a1ad
      highlight: Hot           // primary target
    }
    targets {
      targetInstanceId: 2      // player seat 2 (self)
      legalAction: Select_a1ad
      highlight: Cold          // secondary / self-target
    }
    minTargets: 1
    maxTargets: 1
    prompt {
      promptId: 11869
      parameters {
        parameterName: "CardId"
        type: Number
        numberValue: 292       // spell instanceId on stack
      }
    }
    targetingAbilityGrpId: 86613
    targetingPlayer: 2
  }
  sourceId: 292
  abilityGrpId: 75525
}
allowCancel: Abort
allowUndo: true
```

Key observations:
- Two legal targets: both players (no creatures on battlefield at this point)
- `highlight: Hot` on opponent (seat 1), `highlight: Cold` on self (seat 2)
- `minTargets=1, maxTargets=1` — single-target spell
- `targetingAbilityGrpId=86613` is Shock's ability grpId (distinct from card grpId 75525)
- `abilityGrpId=75525` at the req level = the card grpId

---

## SelectTargetsResp (C→S) — index 9196, gsId=64

```json
{
  "targetIdx": 1,
  "targetInstanceIds": [1]
}
```

- `targetIdx=1` matches the single `targets` group's `targetIdx`
- `targetInstanceIds=[1]` = player seat 1 instanceId

---

## Re-prompt SelectTargetsReq — index 9198, msgId=95, gsId=65

Sent after SelectTargetsResp. New gsId (65 vs 64 initial). Key differences from initial:

```proto
selectTargetsReq {
  targets {
    targetIdx: 1
    targets {
      targetInstanceId: 1
      legalAction: Unselect    // was Select_a1ad — now shows as "deselectable"
      // highlight: absent     // was Hot
    }
    // targetInstanceId: 2 absent — only the selected target remains
    minTargets: 1
    maxTargets: 1
    selectedTargets: 1         // NEW field — count of committed selections
    prompt { ... }             // same promptId 11869 / CardId=292
    targetingAbilityGrpId: 86613
    targetingPlayer: 2
  }
  sourceId: 292
  abilityGrpId: 75525
}
allowCancel: Abort
allowUndo: true
```

Changes on re-prompt vs initial:
- `gsId` bumped (64 → 65)
- `msgId` bumped (93 → 95)
- Selected target entry changes `legalAction` to `Unselect` (de-select is now available)
- `highlight` removed from selected target
- Unselected targets removed from the list (only selected entries remain)
- `selectedTargets: 1` added to indicate committed count
- Accompanying GSM (94/gsId=65) contains only `actions` diff — no zone or object changes

---

## SubmitTargetsReq (C→S) — index 9199, gsId=65

Type-only. No payload fields. The `gsId` references the re-prompt's gsId (65).

```json
{
  "greType": "SubmitTargetsReq",
  "gsId": 65
}
```

---

## Post-submit GSM — index 9202, msgId=96, gsId=66 (SendHiFi)

This is the first substantive S→C message after SubmitTargetsReq.

Annotations on gsId=66:
```
PlayerSubmittedTargets(affectorId=2, affectedIds=[292])   // targeting complete
AbilityInstanceCreated(affectorId=285, affectedIds=[293]) // mana ability fired
TappedUntappedPermanent(affectorId=293, affectedIds=[285], tapped=1)
UserActionTaken(affectorId=2, affectedIds=[293], actionType=4, abilityGrpId=1004) // ActivateMana
ManaPaid(affectorId=285, affectedIds=[292], color=4)      // Red mana paid
AbilityInstanceDeleted(affectorId=285, affectedIds=[293])
UserActionTaken(affectorId=2, affectedIds=[292], actionType=1, abilityGrpId=0)    // Cast
```

Persistent annotation added:
```
TargetSpec(affectorId=292, affectedIds=[1], abilityGrpId=86613, index=1,
           promptParameters=292, promptId=11869)
```

This is the persistent `TargetSpec` that records the committed target for the spell. It survives until Shock resolves (deleted in gsId=68 via `diffDeletedPersistentAnnotationIds`).

Resolution at gsId=68 (SendHiFi):
```
ResolutionStart(affectorId=292, grpid=75525)
DamageDealt(affectorId=292, affectedIds=[1], damage=2, type=2, markDamage=1)
SyntheticEvent(affectorId=292, affectedIds=[1], type=1)
ModifiedLife(affectorId=292, affectedIds=[1], life=-2)
ResolutionComplete(affectorId=292, grpid=75525)
ObjectIdChanged(affectorId=2, affectedIds=[292], orig_id=292, new_id=294)
ZoneTransfer(affectorId=2, affectedIds=[294], zone_src=27, zone_dest=37, category=Resolve)
```
Stack zone becomes `[]`. Shock lands in graveyard (zoneId=37) as instanceId=294.

---

## Is SelectTargetsResp iterative or final?

**Two-phase, not iterative.** Between a SelectTargetsReq and SubmitTargetsReq for gsId=64/65:

1. `SelectTargetsReq` (gsId=64) — presents candidates
2. `SelectTargetsResp` (C→S, gsId=64) — client commits a target selection
3. `GameStateMessage` (gsId=65) — server acknowledges with actions diff
4. `SelectTargetsReq` (gsId=65) — **re-prompt** showing confirmed selections with `legalAction: Unselect`
5. `SubmitTargetsReq` (C→S, gsId=65) — client finalises

Exactly **one** `SelectTargetsResp` appears between initial `SelectTargetsReq` and `SubmitTargetsReq`. The re-prompt at step 4 does NOT elicit another `SelectTargetsResp` — the client goes straight to `SubmitTargetsReq`.

For a multi-target spell (maxTargets > 1), steps 2–4 would repeat for each target selection before the final `SubmitTargetsReq`.

---

## Echo-back GSM after SubmitTargetsReq?

**No.** Unlike DeclareAttackers/DeclareBlockers, targeting does NOT use the echo-back pattern.

After `SubmitTargetsReq` the server sends a **batch of QueuedGameStateMessages + GameStateMessages in a single frame** (transactionId matches the SubmitTargetsReq's requestId). The batch contains:
- Two `QueuedGameStateMessage` entries (seat 1 catch-up for gsId=64 and gsId=65)
- Nine `GameStateMessage` entries (gsId=66–74), covering mana payment, stack priority passes, resolution, and next turn setup

No separate echo-back GSM with `updateType: SendAndRecord` is sent between the SubmitTargetsReq and the resolution batch.

---

## Two-Phase Summary

```
Phase 1 — Target Selection:
  S→C  SelectTargetsReq (gsId=N)     present candidate pool
  C→S  SelectTargetsResp (gsId=N)    select target(s)
  S→C  GSM Diff (gsId=N+1)           ack (actions only)
  S→C  SelectTargetsReq (gsId=N+1)   re-prompt: selected entries → legalAction=Unselect

Phase 2 — Submit:
  C→S  SubmitTargetsReq (gsId=N+1)   finalise (no payload)
  S→C  [batch] QueuedGSM + GSMs      mana paid, TargetSpec added, spell resolves
```

The `gsId` advances once between SelectTargetsResp and re-prompt SelectTargetsReq. The `SubmitTargetsReq` always uses the **re-prompt's gsId**, not the initial SelectTargetsReq's gsId.
