# Counterspell Wire Spec — 2026-03-21

## Summary

Scoped from recording `recordings/2026-03-21_23-30-58`. Essence Scatter (grpId 93866,
instanceId 396, seat 1) was drawn on turn 15 draw step but **never cast** — the game
ended (IntermissionReq) during turn 15 combat. No counterspell event exists anywhere in
this recording.

**Corpus-wide search result:** Searched all recordings for a `"Countered"` ZoneTransfer
category or any Stack→GY transfer with a category other than `"Resolve"`. Finding:
zero occurrences of `"Countered"` as a category string anywhere in the recording
corpus. Every Stack→GY ZoneTransfer annotation in every recording uses `"Resolve"`.

No live wire data is available to confirm the `"Countered"` category string.

---

## What We Know From Code and Protocol

### Real server — resolved instant/sorcery (confirmed)

From `recordings/2026-03-07_23-50-22` (gsId 76, Opt resolving):

```
ResolutionStart   affectedIds=[297] affectorId=297  details={grpid: 75484}
...effect annotations (card draw)...
ResolutionComplete affectedIds=[297] affectorId=297  details={grpid: 75484}
ObjectIdChanged   affectedIds=[297] affectorId=2    details={orig_id:297, new_id:302}
ZoneTransfer      affectedIds=[302] affectorId=2    details={zone_src:27, zone_dest:37, category:"Resolve"}
```

Pattern: ResolutionStart → effect annotations → ResolutionComplete → ObjectIdChanged →
ZoneTransfer("Resolve"). The resolving spell carries `affectorId = seatId (2)` on the
ZoneTransfer. Effect annotations (card draws, etc.) use the spell's iid as affectorId.

### Expected sequence for a counterspell cast (hypothesized, not confirmed from wire)

When seat 1 casts Essence Scatter targeting an opponent creature spell on stack:

**Phase 1 — Essence Scatter resolves:**
```
ResolutionStart   affectedIds=[<ES_iid>]  details={grpid:93866}
ResolutionComplete affectedIds=[<ES_iid>]  details={grpid:93866}
ObjectIdChanged   affectedIds=[<ES_iid>]  affectorId=<seatId>
ZoneTransfer      affectedIds=[<new_ES_iid>] details={zone_src:stack, zone_dest:GY, category:"Resolve"}
```

**Phase 2 — Countered spell moves to GY:**
```
ObjectIdChanged   affectedIds=[<target_iid>]  affectorId=<ES_iid?>
ZoneTransfer      affectedIds=[<new_target_iid>] details={zone_src:stack, zone_dest:GY, category:"Countered"}
```

The `affectorId` on the countered spell's ZoneTransfer is expected to be the
counterspell's iid (post-ID-change), but this is unconfirmed.

---

## Forge Event Chain (analyzed from source)

When a counterspell resolves in Forge:

1. `CounterEffect.resolve()` is called
2. It does NOT fire `GameEventSpellResolved` for the countered spell
3. It calls `game.getAction().moveToGraveyard(c, srcSA, params)` directly
4. `moveToGraveyard` fires `GameEventCardChangeZone` (Stack→GY)

So the event pipeline for the countered spell is:
```
GameEventCardChangeZone(Stack→GY)
  → GameEventCollector: falls through to generic ZoneChanged(Stack, GY)
  → AnnotationBuilder.zoneChangedCategory: Stack→GY → TransferCategory.Countered
```

The counterspell itself (Essence Scatter) goes through the normal `SpellResolved`
path and gets `TransferCategory.Resolve` (Stack→GY).

---

## Code Status

### TransferCategory.Countered — correctly defined

`TransferCategory.kt` line 18: `Countered("Countered")` — label matches expected
string. Two code paths set this category:

1. `AnnotationBuilder.categoryFromEvents()` line 89 — `is GameEvent.SpellCountered` arm.
   **Dead code**: `GameEvent.SpellCountered` is defined in `GameEvent.kt` but never
   emitted by `GameEventCollector`. No `GameEventSpellCountered` exists in Forge.

2. `AnnotationBuilder.zoneChangedCategory()` line 150 — `ev.from == Zone.Stack &&
   ev.to == Zone.Graveyard → TransferCategory.Countered`. This is the **live path**.

### Annotation sequence for Countered category

`AnnotationPipeline.annotationsForTransfer()` lines 325-334: `TransferCategory.Countered`
falls into the same branch as `Destroy`, `Sacrifice`, etc.:

```kotlin
TransferCategory.Destroy, TransferCategory.Sacrifice, TransferCategory.Countered, ...
-> {
    if (origId != newId) annotations.add(objectIdChanged(...))
    annotations.add(zoneTransfer(newId, srcZone, destZone, category.label, affectorId))
}
```

So we emit: `ObjectIdChanged` (if iid changes) + `ZoneTransfer("Countered")`.
No `ResolutionStart`/`ResolutionComplete` for the countered spell — correct, those
belong to the counterspell itself (which resolves normally).

---

## Open Questions / Unverified

1. **"Countered" category string** — assumed correct, never confirmed from wire data.
   Real server may use a different string (e.g. `"Counter"`, `"SBA_Counter"`).

2. **affectorId on the countered ZoneTransfer** — unknown. Candidates:
   - counterspell's iid (most likely)
   - 0 / absent
   - seatId

3. **Does ResolutionStart/Complete fire for the countered spell?** — no, based on
   Forge not firing SpellResolved for it, but unconfirmed from wire.

4. **Targeting annotation** — when Essence Scatter targets a creature spell on stack,
   a TargetSpec annotation should appear when the spell is cast (Stack entry). Not
   verified from wire.

5. **Fizzle path** — `SpellResolved(hasFizzled=true)` fires when all targets are
   illegal at resolution time (fizzle ≠ counter). This correctly maps to
   `TransferCategory.Countered` via `AnnotationBuilder.categoryFromEvents()` line 72.
   Code path: correct. Wire confirmation: absent.

---

## Dead Code to Clean Up (flag for engine-bridge agent)

`GameEvent.SpellCountered` (GameEvent.kt:190) and the matching arm in
`AnnotationBuilder.categoryFromEvents()` (line 89) are unreachable. No Forge event
triggers `SpellCountered` emission. The live counter path is the generic
`ZoneChanged(Stack→GY)` heuristic. Recommend removing the dead event variant and
the dead arm to reduce confusion.

---

## Catalog Status

`catalog.yaml` entry:

```yaml
counterspell:
  status: wired
  notes: "Countered category fires, spell → graveyard."
```

Status remains `wired` — the logic is structurally correct. Category string is
unconfirmed from wire data; label `"Countered"` is the assumed canonical value.
Update to `confirmed` only after a recording captures an actual counterspell cast.

---

## Recording Needed

To fully confirm: a recording where seat 1 casts Essence Scatter (or any
counterspell) targeting a creature spell on the opponent's stack, the opponent does
not respond, and the spell resolves countering the target.
