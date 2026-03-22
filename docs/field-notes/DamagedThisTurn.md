## DamagedThisTurn — field note

**Status:** PARTIALLY IMPLEMENTED — emitted in wrong field (transient `annotations` instead of `persistentAnnotations`), missing `affectorId`, and not accumulated correctly across a turn
**Instances:** 78 across 4 sessions (40 unique GSMs, duplicated per two-seat echo pattern)
**Proto type:** AnnotationType.DamagedThisTurn (type 90)
**Field:** persistentAnnotations only — never in transient `annotations`

### What it means in gameplay

The server places a persistent badge on every creature that was dealt damage this turn. The client uses it to render the red "damaged" indicator visible on creatures in combat (and outside it — burn spells also trigger this). The badge tracks a cumulative set: a single annotation instance accumulates all creature `instanceIds` that took damage since the last Upkeep, growing as more creatures are hit within the same turn. It is cleared at the start of each Upkeep.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| (none) | Always | — | No detail keys in any instance — all metadata encoded in affectorId/affectedIds |

### Shape

- **Type array:** always `["DamagedThisTurn"]` (single type, never multi-typed)
- **affectorId:** always `28` — this is the Battlefield zone ID, not a card instanceId. Signals "the battlefield is the context for this damage state"
- **affectedIds:** 1–3 elements; the set of creature `instanceIds` damaged this turn. Grows cumulatively within a turn — new victims are appended; no reset until Upkeep deletion
- **details:** always `{}` (empty)
- **Annotation ID:** small stable integer (48 or 65 observed). The server reuses the same annotation ID throughout the turn, updating the `affectedIds` list in-place rather than deleting and recreating

### Lifecycle

1. First creature takes damage in CombatDamage step → annotation appears in `persistentAnnotations` with that one `instanceId`
2. Subsequent creatures damaged in the same turn → same annotation ID reappears in `persistentAnnotations` with the new victim added to `affectedIds`
3. At beginning of Upkeep (next turn) → annotation ID appears in `diffDeletedPersistentAnnotationIds`; annotation vanishes
4. New damage that turn → new annotation ID created, cycle repeats

A turn with 3 waves of combat damage shows the progression:
```
CombatDamage turn N:  affectedIds=[312]
CombatDamage turn N:  affectedIds=[312, 317]   (second combat / trample / multi-block)
Upkeep turn N+1:      deleted (appears in diffDeletedPersistentAnnotationIds)
```

### Triggers

- **Combat damage step (CombatDamage)** — 62/78 instances. Both creatures in a block take damage → both appear in affectedIds
- **First strike damage step (FirstStrikeDamage)** — 4/78 instances. First strike creatures dealt damage appear before normal CombatDamage wave
- **`phase=None/None`** — 9/78 instances. These are echo-back copies of the combat damage GSM sent to the passive seat (echo pattern; turnInfo absent)
- **Main2** — 3/78 instances. Annotation persisting into the post-combat phase in the same turn (not a new creation — `affectorId` and content unchanged from prior CombatDamage frame)

Non-combat damage (burn spells, activated abilities) would also trigger this but no instances were captured in these sessions — the mechanic is identical; the annotation would appear in the spell resolution GSM.

### Phase distribution (first-appearance frames only)

| Phase/Step | Count | Note |
|-----------|-------|------|
| Combat/CombatDamage | 62 | Normal case |
| Combat/FirstStrikeDamage | 4 | First strike resolution |
| None/None | 9 | Echo-back GSMs (no turnInfo) |
| Main2/None | 3 | Carry-through frames (existing badge, not new creation) |

### Cards observed (sample — affected creatures)

| Card | grpId | Times damaged | Session |
|------|-------|---------------|---------|
| Wall of Runes | 75478 | 10 | multiple |
| Rumbling Baloth | 75544 | 10 | multiple |
| Sentinel Spider | 75545 | 10 | multiple |
| Warden of Evos Isle | 75479 | 8 | multiple |
| Armored Whirl Turtle | 75465 | 6 | multiple |
| Affectionate Indrik | 75530 | 4 | multiple |
| Charmed Stray | 75446 | 4 | multiple |
| Angelic Guardian | 75443 | 4 | multiple |
| Brineborn Cutthroat | 93865 | 4 | multiple |

### Our code status

**Builder:** exists — `AnnotationBuilder.damagedThisTurn(instanceId)` at line 742. Missing `affectorId` parameter (battlefield zone 28 is not set).

**Pipeline:** called at `AnnotationPipeline.kt:457` inside `combatAnnotations()`, adding to `annotations` (transient). Three divergences from real server:

1. **Wrong field:** emitted into `CombatAnnotationResult.annotations` → ends up in transient `annotations` in the proto. Real server: `persistentAnnotations` only.
2. **Missing affectorId:** builder does not set `affectorId=28` (Battlefield zone ID).
3. **Not accumulated:** one annotation emitted per `cardDamage` event per GSM, not a single growing set. Real server maintains one stable annotation ID that accumulates all victims within the turn.

### Dependencies

- **`diffDeletedPersistentAnnotationIds`** — must include the annotation ID at Upkeep start to clear the badge. Currently no cleanup mechanism since the annotation is transient.
- **`AnnotationStore` / `bridge.annotations`** — persistent annotation lifecycle system (already used for `EnteredZoneThisTurn`, `ColorProduction`, `Counter`, `Attachment`). DamagedThisTurn needs the same treatment.
- **`GameEventCardDamaged`** — already captured (feeds into `cardDamage` list in `combatAnnotations`). Event source is correct.

### Wiring assessment

**Difficulty:** Medium

The event source is correct. The fix requires:

1. Move `damagedThisTurn` from `CombatAnnotationResult.annotations` (transient) into the persistent annotation path — similar to how `enteredZoneThisTurn` is handled in `annotationsForTransfer`.
2. Add `affectorId=28` to the builder (constant — always the Battlefield zone ID).
3. Implement accumulation: maintain a `damagedThisTurn` persistent annotation in `AnnotationStore`, accumulate affected IDs across combat events in the same turn, re-emit the updated annotation each GSM it changes.
4. Clear at Upkeep start — detect the phase transition to `Beginning/Upkeep` and add the annotation ID to `diffDeletedPersistentAnnotationIds`.

The accumulation across a turn is the hard part — it requires turn-scoped state in the annotation store or bridge, tracking which creatures have been damaged since the last Upkeep.

### Open questions

- Does non-combat damage (burn spells, direct damage instants) use the same annotation, same `affectorId=28`? No non-combat instances in these sessions. Worth capturing a session with Lightning Bolt or similar.
- When a creature dies from combat damage and gets an `ObjectIdChanged` (new instanceId in graveyard), does the `affectedIds` still show the old instanceId or the new one? Not observed in these sessions.
- If a creature is damaged and then healed back to full (e.g. regenerate), does the annotation stay or get cleared mid-turn? Unclear.
