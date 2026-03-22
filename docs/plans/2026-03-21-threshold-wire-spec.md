# Threshold Wire Spec — Dreadwing Scavenger

Recording: `recordings/2026-03-21_23-30-58/`
Card: Dreadwing Scavenger (grpId 93831, seat 1)
Instances observed: iid 160 (hand, unplayed), iid 295 (played, full lifecycle), iid 310 (drawn late game)

---

## 1. Instance Lifecycle

| Line | gsId | Event |
|------|------|-------|
| 7 | 2 | iid 160 in opening hand (zone 31) |
| 104 | 84 | iid 160 → cast, becomes iid 295 on stack (zone 27) |
| 106 | 86 | iid 295 resolves → zone 28 (Battlefield), `AbilityWordActive` born, loot trigger iid 299 on stack |
| 148 | 126 | iid 310 drawn from library (second copy) |

---

## 2. Loot Trigger (ETB/Attack → Draw + Discard)

Trigger grpId: **189406** (loot ability instance created as `AbilityInstanceCreated`).

### Draw step (gsId=88, line 217)
```
ResolutionStart   affectorId=295-trigger, grpid=189406
ObjectIdChanged   orig_id=168 → new_id=300       (library card gets new iid)
ZoneTransfer      affectedIds=[300]  zone_src=32→31  category="Draw"
```

### Discard step (gsId=89, line 223)
```
ObjectIdChanged   orig_id=163 → new_id=301       (hand card gets new iid on discard)
ZoneTransfer      affectedIds=[301]  zone_src=31→33  category="Discard"
ResolutionComplete  affectorId=trigger, grpid=189406
AbilityInstanceDeleted  affectorId=295, affectedIds=[299]
```

Draw and discard are in **separate gsId diffs** (88 vs 89). The discard frame carries `ResolutionComplete` and `AbilityInstanceDeleted`.

Later loot resolutions follow the same pattern (e.g. iid 343 at gsId=202/203).

---

## 3. ETB Annotations (Resolve frame, gsId=86, line 213)

```
ResolutionStart    affectorId=295, grpid=93831
ResolutionComplete affectorId=295, grpid=93831
LayeredEffectCreated  affectorId=295, affectedIds=[7002]   ← P/T effect slot
LayeredEffectCreated  affectorId=295, affectedIds=[7003]   ← AddAbility slot
ZoneTransfer       affectedIds=[295]  zone_src=27→28  category="Resolve"
AbilityInstanceCreated  affectorId=295, affectedIds=[299]  details.source_zone=28
```

Two `LayeredEffectCreated` fire on ETB — one for the future +1/+1 mod, one for the deathtouch add. Both appear as transient annotations **before the threshold is met** — they announce effect slots but the persistent `LayeredEffect` annotations only populate when the condition activates.

Also in `persistentAnnotations` of this frame:
```
EnteredZoneThisTurn  affectorId=28, affectedIds=[295]
TriggeringObject     affectorId=299, affectedIds=[295]  details.source_zone=28
AbilityWordActive    id=203, affectorId=295, affectedIds=[295]
  details: { threshold: 7, value: 0, AbilityGrpId: 175886, AbilityWordName: "Threshold" }
```

---

## 4. Threshold Mechanic — Full Protocol

### 4a. AbilityWordActive persistent annotation

From the moment Scavenger enters the battlefield, a **persistent annotation** id=203 of type `AbilityWordActive` is present in every diff:

```json
{
  "id": 203,
  "types": ["AbilityWordActive"],
  "affectorId": 295,
  "affectedIds": [295],
  "details": {
    "threshold": 7,
    "value": <current GY count>,
    "AbilityGrpId": 175886,
    "AbilityWordName": "Threshold"
  }
}
```

- `threshold`: target count (always 7)
- `value`: current graveyard card count (observed: 0 → 1 → 2 → 3 → 6 → 7 → 8 → 11 → 12 → 13 → 15)
- `AbilityGrpId`: 175886 (Threshold ability grpId on this card)
- `AbilityWordName`: "Threshold" (literal string)

The `value` field updates in every diff as cards enter the graveyard. The annotation persists until the creature leaves the battlefield (deleted at game end gsId=353 along with cleanup of other permanents).

### 4b. What fires when value crosses threshold (value=7, gsId=203, line 531)

In the **same diff** where the 7th card lands in the graveyard (discard during loot resolution), the object data changes AND two new persistent annotations appear:

**Object change (silent — no dedicated crossing annotation):**
```
iid 295: power=2, toughness=2, uniqueAbilityCount=3
→ iid 295: power=3, toughness=3, uniqueAbilityCount=4
```

No `LayeredEffectCreated`, `PowerToughnessModCreated`, or `AddAbility` transient annotation fires at the crossing moment. The stat update is delivered as a plain object diff.

**New persistent annotations added to this diff:**
```json
{
  "id": 206,
  "types": ["ModifiedToughness", "ModifiedPower", "LayeredEffect"],
  "affectorId": 295,
  "affectedIds": [295],
  "details": { "effect_id": 7002 }
}
```
```json
{
  "id": 208,
  "types": ["AddAbility", "LayeredEffect"],
  "affectorId": 295,
  "affectedIds": [295],
  "details": {
    "originalAbilityObjectZcid": 295,
    "UniqueAbilityId": 222,
    "grpid": 1,
    "effect_id": 7003
  }
}
```

The `effect_id` values (7002, 7003) match the slots pre-announced by `LayeredEffectCreated` at ETB.

The `AddAbility` LayeredEffect (`effect_id`=7003) carries:
- `UniqueAbilityId`: 222 — deathtouch ability index
- `grpid`: 1 — base ability grpId (not a specific card)
- `originalAbilityObjectZcid`: 295 — source object

### 4c. AbilityWordActive update at the crossing

In the same gsId=203 diff, id=203 updates to `value=7`:
```json
{ "threshold": 7, "value": 7, "AbilityGrpId": 175886, "AbilityWordName": "Threshold" }
```

This is the only signal that the condition is newly met — there is no separate "threshold crossed" event type.

### 4d. Threshold toggling off

Not observed toggling off in this recording. The `AbilityWordActive` value continues to climb past 7 (8, 11, 12, 13, 15) without toggling back. Prediction for toggling off (e.g. exile from GY):
- `AbilityWordActive.value` would drop below 7
- `LayeredEffect` persistent annotations (id 206, 208) would appear in `diffDeletedPersistentAnnotationIds`
- Object `power`/`toughness` would revert to base (2/2) and `uniqueAbilityCount` back to 3
- No dedicated "effect destroyed" transient annotation expected — the ETB `LayeredEffectCreated` was the only transient signal

---

## 5. Summary: What the Server Does for Threshold

| Signal | When | Type | Notes |
|--------|------|------|-------|
| `LayeredEffectCreated` (×2) | ETB | Transient | Pre-announces effect slots 7002, 7003; no details |
| `AbilityWordActive` | ETB onward | Persistent | Born with `value=0`; `value` updates each GY change |
| Object p/t change | value≥threshold | Object diff | Silent — no dedicated transient |
| `[ModifiedToughness, ModifiedPower, LayeredEffect]` | value≥threshold | Persistent | effect_id=7002 |
| `[AddAbility, LayeredEffect]` | value≥threshold | Persistent | effect_id=7003, UniqueAbilityId=222 (deathtouch) |
| GY count live update | Each GY change | Persistent update | `AbilityWordActive.value` increments |

---

## 6. Code Gap: AbilityWordActive Not Implemented

`AbilityWordActive` (proto enum value 39) exists in `messages.proto` but has **zero usages** in production `matchdoor/` Kotlin source. `AnnotationBuilder` has no method for it.

### What needs to be built

1. **`AnnotationBuilder.abilityWordActive()`** — persistent annotation builder:
   - types: `[AbilityWordActive]`
   - affectorId = creature instanceId
   - affectedIds = [creature instanceId]
   - details: `threshold`, `value`, `AbilityGrpId`, `AbilityWordName`

2. **GY count tracking** — `StateMapper` or `EffectTracker` needs to count cards in the controller's graveyard and detect when threshold ability cards are on battlefield.

3. **Persistent LayeredEffect activation** — when `value` crosses `threshold`, add `[ModifiedToughness, ModifiedPower, LayeredEffect]` and `[AddAbility, LayeredEffect]` to `PersistentAnnotationStore`. When it drops back below, remove them.

4. **Object stat reflection** — Forge already handles the actual +1/+1 and deathtouch (via continuous effects engine); `StateMapper` just needs to not suppress `uniqueAbilityCount` bumps for threshold creatures.

5. **No transient annotation at crossing** — confirmed by wire. The crossing is communicated purely through the persistent annotation state change + object diff.

### EffectTracker scope

The current `EffectTracker` handles spell-speed P/T boosts (Prowess, Giant Growth). Threshold is a static continuous effect conditioned on a game-state predicate — a different category. It needs its own persistent annotation lifecycle separate from `EffectTracker.BoostEntry`.

---

## 7. Loot Trigger grpId Reference

- Loot trigger (ETB/attack, draw+discard): **grpId 189406**
- Threshold ability: **AbilityGrpId 175886**
- Dreadwing Scavenger card: **grpId 93831**
