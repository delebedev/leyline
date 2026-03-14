# Kicker Protocol Wire Spec

Extracted from proxy recordings. All observations are server→client (S→C) unless marked C→S.

## Cards observed

| grpId | Name | Kicker ability grpId | Kicker cost |
|-------|------|----------------------|-------------|
| 93905 | Burst Lightning | 2852 | `{o4}` |
| 93738 | Sun-Blessed Healer | 9313 | `{o1oW}` |

## Full message sequence: kicker spell cast

Consistent across all 4 observed instances. Shown with indices from 2026-03-06_22-37-41 (index 1100).

```
[N-2] ActionsAvailableReq        gsId=N-1   — includes Cast action for the spell
[N-1] C→S PerformActionResp      gsId=N-1   — client picks Cast
[0]   GSM (Diff, Send)           gsId=N     — spell moves Hand→Stack (ObjectIdChanged + ZoneTransfer CastSpell)
[1]   CastingTimeOptionsReq      gsId=N     — kicker prompt (SAME gsId as preceding GSM)
[2]   C→S CastingTimeOptionsResp gsId=N     — client responds (bare body, see below)
[3]   GSM (Diff, Send)           gsId=N+1   — PlayerSelectingTargets annotation added
[4]   SelectTargetsReq           gsId=N+1   — targeting prompt (in same file as GSM above)
[5]   C→S SelectTargetsResp      gsId=N+1   — client selects target
[6]   GSM (Diff, Send)           gsId=N+2   — empty diff (client-side state hold)
```

Note: in recording 2026-03-01_00-11-05 (Sun-Blessed Healer, a creature), steps [3]-[6] are absent — no targeting. Instead the post-resp GSM is `SendHiFi` and immediately shows mana paid + spell resolved.

## CastingTimeOptionsReq message anatomy (Kicker)

```json
{
  "greType": "CastingTimeOptionsReq",
  "msgId": 190,
  "gsId": 142,
  "castingTimeOptions": [
    {
      "ctoId": 2,
      "type": "Kicker",
      "affectedId": 305,
      "affectorId": 305,
      "grpId": 2852
    },
    {
      "ctoId": 0,
      "type": "Done",
      "isRequired": true
    }
  ],
  "promptId": 23,
  "systemSeatIds": [1]
}
```

Key fields:
- `ctoId`: sequential integer assigned per-spell-cast, starts at 2 (not 0 or 1) in observed data. The `Done` option always has `ctoId=0`. Across instances: ctoId=2 (22-37-41 idx 1100), ctoId=2 (22-37-41 idx 1566), ctoId=4 (00-18-46 idx 280), ctoId=2 (00-11-05 idx 134). **Not a bitfield, just an ordinal.**
- `type`: `"Kicker"` — string enum. Compare `"Modal"`, `"Bargain"` for other option types.
- `affectedId` == `affectorId` == the stack instance ID of the spell being cast.
- `grpId`: the kicker **ability** grpId (not the card grpId). Must match the `castingTimeOptions[].grpId` in `castingTimeOptions`.
- `isRequired`: absent for Kicker (kicker is optional). Present and `true` for `Done`.
- No nested `modal` / `SelectNReq` / `NumericInputReq` inside a kicker option — the kicker option is flat.
- `promptId`: always `23` for CastingTimeOptionsReq across all recordings.

## CastingTimeOptionsResp (C→S)

**Kicker declined (all observed instances):**
```json
{
  "greType": "CastingTimeOptionsResp",
  "gsId": 142,
  "clientType": "CastingTimeOptionsResp"
}
```
Bare — no `selectedOptions`, no `ctoId`. Client sends empty body when choosing `Done` (decline kicker).

**Kicker accepted:** not observed in any proxy recording in this dataset. See notes below.

## Comparison: Kicker vs Modal CastingTimeOptionsReq

| Field | Kicker | Modal |
|-------|--------|-------|
| `type` | `"Kicker"` | `"Modal"` |
| `isRequired` on option | absent | `true` |
| `grpId` on option | kicker ability grpId | spell grpId (same as card) |
| nested `modal` block | absent | present (`abilityGrpId`, `minSel`, `maxSel`, `options[]`) |
| `Done` option present | yes (`ctoId=0`) | absent (modal is always required) |
| post-resp targeting | present (if targeted spell) | present |

Modal example from 2026-03-01_00-18-46 idx 117 (grpId=93913 Goblin Surprise):
```json
{
  "ctoId": 2,
  "type": "Modal",
  "affectedId": 295,
  "affectorId": 295,
  "grpId": 93913,
  "isRequired": true,
  "modal": {
    "abilityGrpId": 175922,
    "minSel": 1,
    "maxSel": 1,
    "options": [{"grpId": 23611}, {"grpId": 1360}]
  }
}
```

## GSM at stack placement (index 0 above)

- `gsmType`: `"Diff"`
- `updateType`: `"Send"` (not SendAndRecord)
- zones: Stack gains new instanceId, Hand loses old instanceId, Limbo gains old instanceId
- annotations: `ObjectIdChanged` (old→new) + `ZoneTransfer` (zone_src=Hand zone, zone_dest=27 Stack, category=`CastSpell`)
- persistentAnnotations: `EnteredZoneThisTurn` on new stack instanceId
- **No mana annotations yet** — mana payment happens after CastingTimeOptionsResp

## Post-CastingTimeOptionsResp GSM (kicker declined path)

`updateType`: `"Send"` (gsId incremented). Diff-only:
- adds `PlayerSelectingTargets` annotation (for targeted spells)
- no mana payment annotations — mana payment deferred until after target selection

For Sun-Blessed Healer (non-targeted creature, 2026-03-01_00-11-05 idx 140):
- `updateType`: `"SendHiFi"`
- contains full mana payment chain: AbilityInstanceCreated → TappedUntappedPermanent → UserActionTaken → ManaPaid → AbilityInstanceDeleted
- also contains `UserActionTaken` with `actionType=1, abilityGrpId=0` (the cast itself)
- spell immediately goes on stack and resolves in subsequent SendHiFi frames

## Kicker accepted case: not observed

All 4 recording instances show kicker **declined** (bare CastingTimeOptionsResp). No accepted-kicker wire trace available.

When kicker is accepted, expected differences based on protocol patterns:
- `CastingTimeOptionsResp` should include `selectedOptions: [{ctoId: <N>}]` (inference from modal pattern)
- Post-resp GSM would show mana payment for kicker cost in addition to base cost
- `CastingTimeOption` persistent annotation (type 64) should appear on the stack object recording which option was chosen

## CastingTimeOption annotation (type 64)

Per existing field notes: type 64 `CastingTimeOption` is a **persistent annotation** placed on the spell-on-stack to record which option was chosen. Distinct from the `CastingTimeOptionsReq` message. Expected when kicker is accepted; not present in the declined cases observed here.

## Implementation notes

1. Server sends `CastingTimeOptionsReq` **in the same file** (`000004535_MD_S-C_MATCH_DATA.bin`) as the stack-placement GSM. Both are batched in one TCP write. Implies leyline must emit both in one flush.
2. `gsId` does **not** increment between the stack-placement GSM and the `CastingTimeOptionsReq`.
3. `promptId=23` is fixed for CastingTimeOptionsReq (not dynamically assigned per-session).
4. For spells with both Kicker AND Modal (e.g. a hypothetical card), expect multiple entries in `castingTimeOptions` array.
5. Targeting (SelectTargetsReq) does not fire until after CastingTimeOptionsResp — the server gates targeting behind option resolution.

## Source indices

| Recording | Index | Card | Kicker grpId | Outcome |
|-----------|-------|------|--------------|---------|
| 2026-03-06_22-37-41 | 1100 | Burst Lightning (93905) | 2852 | declined |
| 2026-03-06_22-37-41 | 1566 | Burst Lightning (93905) | 2852 | declined |
| 2026-03-01_00-18-46 | 280  | Burst Lightning (93905) | 2852 | declined |
| 2026-03-01_00-11-05 | 134  | Sun-Blessed Healer (93738) | 9313 | declined |
