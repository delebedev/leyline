# Surveil Zone Transfer — Verification Field Notes

Date: 2026-03-08
Recording: `recordings/2026-03-07_23-50-22/`
Plan doc: `docs/plans/2026-03-08-surveil-zone-transfer.md`

## Summary

All four claims in the plan doc are **confirmed correct** against the raw recording data.
The root-cause analysis of our diff pipeline gap is also correct, with one
additional detail about the `Surveil` category label that the plan doesn't cover.

---

## 1. Index 247 — ObjectIdChanged (confirmed)

Raw JSON from `just proto-decode-recording … | grep index:247`:

```json
"annotations": [
  {"id":399, "types":["ObjectIdChanged"], "affectorId":328, "affectedIds":[172],
   "details":{"orig_id":172, "new_id":329}},
  ...
]
```

Confirmed: `ObjectIdChanged` present, `orig_id:172 → new_id:329`, `affectorId:328`
(the ability instance on the stack).

The plan doc says `affectorId:328` — matches exactly.

## 2. Index 247 — ZoneTransfer (confirmed)

```json
{"id":400, "types":["ZoneTransfer"], "affectorId":328, "affectedIds":[329],
 "details":{"zone_src":32, "zone_dest":33, "category":"Surveil"}}
```

Confirmed:
- `zone_src:32` = Library (ZoneIds.P1_LIBRARY = 32)
- `zone_dest:33` = Graveyard (ZoneIds.P1_GRAVEYARD = 33)
- `category:"Surveil"` — exact string, case-sensitive
- `affectedIds:[329]` — uses the NEW instanceId (post-realloc)

Plan doc says `zone_src:32, zone_dest:33, category:"Surveil"` — confirmed.

## 3. Index 103 — no ZoneTransfer (confirmed)

Index 103 is the post-GroupResp diff for "keep on top" (instanceId 169, grpId 94007):

```json
"annotations": [
  {"types":["MultistepEffectComplete"], ...},
  {"types":["ResolutionComplete"], ...},
  {"types":["AbilityInstanceDeleted"], ...}
]
```

No `ZoneTransfer`, no `ObjectIdChanged`. Card 169 stays in Library —
zone membership of Library does NOT change (card stays). Confirmed.

## 4. Both old + new objects in index 247 diff (confirmed)

```json
"objects": [
  {"instanceId":172, "grpId":93948, "zoneId":30, "visibility":"Private",
   "viewers":[1], ...},           ← old id, now in Limbo (zoneId 30)
  {"instanceId":329, "grpId":93948, "zoneId":33, "visibility":"Public", ...}
                                   ← new id, in Graveyard (zoneId 33)
]
```

Zones also show: Limbo (30) contains 172; Library (32) has 49 cards (172 removed);
Graveyard (33) = [329, 326].

Plan doc says "old object placed in Limbo zone, new object in Graveyard" — confirmed.

---

## 5. Index 100-103 structure (additional detail vs plan doc)

The plan doc says index 100 is the "reveal diff" for instanceId 169. Confirmed:

```
100: Diff gsId=63 — objects:[instanceId:169, zoneId:32, Private, viewers:[1]]
     annotations: ResolutionStart + MultistepEffectStarted
101: GroupReq gsId=63, promptId=129
102: GroupResp (C→S)
103: Diff gsId=64 — zones:[Stack empty], annotations: MultistepEffectComplete +
     ResolutionComplete + AbilityInstanceDeleted; diffDeletedInstanceIds:[294]
```

No zone change for 169 in 103 — it stays in Library. Matches plan.

---

## 6. detectZoneTransfers gap analysis (confirmed + clarified)

`AnnotationPipeline.detectZoneTransfers` iterates over `gameObjects`. It detects a
zone transfer only if:

```kotlin
val prevZone = bridge.getPreviousZone(obj.instanceId)
if (prevZone != null && prevZone != obj.zoneId) { ... }
```

For this to fire, the instanceId (172) must appear in `gameObjects` in the current
snapshot **and** have a recorded previous zone.

**The Limbo gap:** During the engine's `arrangeForSurveil` prompt, Forge moves the
card to an internal staging area. At that point, card 172 is removed from Library
but not placed in any zone visible to `buildFromGame`. When `buildFromGame` is
called for the pre-GroupReq reveal diff (index 100/gsId=63), card 172 is in
Library — it appears in the zone `objectInstanceIds` (hidden, no GameObjectInfo
entry in the objects list), so `detectZoneTransfers`' zone-recording loop at lines
131-137 records `172 → Library(32)` for library cards.

After `submitResponse` + `awaitPriority()`, card 172 is now in Graveyard. When
`buildFromGame` runs for the post-GroupResp diff (index 247/gsId=178), card 172
should appear in Graveyard as a GameObjectInfo. BUT — the engine's zone change
happens during the prompt: card 172 leaves Library and enters Graveyard during
`arrangeForSurveil`. The previous snapshot (gsId=177) does NOT include 172 as a
GameObjectInfo (it was hidden in Library). So 172 IS in the `previousZones` map
from the zone-list recording, but when `buildFromGame` builds the current state,
card 172 has already moved. Whether 172 appears in the current `gameObjects` list
depends on whether it's visible in Graveyard — it should be (Graveyard is Public).

**The actual failure mode:** The plan states "it never saw the card leave Library"
because it was removed before the previous snapshot. This is subtly different from
what the code does. The correct description:

- `previousZones[172]` = Library(32) — IS recorded (from zone list tracking in prior
  snapshot, lines 131-137 of `detectZoneTransfers`)
- When post-GroupResp diff runs, `buildFromGame` includes card 172 in gameObjects
  with `zoneId=33` (Graveyard)
- So `prevZone=32 != obj.zoneId=33` → transfer IS detected

But then `categoryFromEvents` is called. The category determination depends on
what `GameEvent.Surveil` carries (seat + counts) — it doesn't carry the forgeCardId
of the moved card. So `categoryFromEvents` won't find a `CardMilled` or specific
event for card 172; it will fall back to `inferCategory(Library→Graveyard)` which
returns `TransferCategory.Mill` (not `Surveil`).

**`TransferCategory.Surveil` does not exist in our enum.** The `TransferCategory`
enum has no `Surveil` variant. The real server sends `category:"Surveil"` as the
detail string. Our `inferCategory` for Library→Graveyard returns `Mill` (label
`"Mill"`), which is the wrong category label.

Additionally, the `ObjectIdChanged` + `ZoneTransfer` annotations ARE emitted for
the `Mill` path (lines 180-183 of `annotationsForTransfer`), but the category
string would be wrong.

---

## 7. Missing TransferCategory.Surveil (new finding, not in plan doc)

`TransferCategory.kt` defines:
```kotlin
enum class TransferCategory(val label: String) {
    ...
    Mill("Mill"),
    ...
    // NO Surveil variant
}
```

The real server sends `category:"Surveil"` (confirmed in index 247). Our pipeline
would produce `category:"Mill"` for Library→Graveyard surveil transfers. This is a
separate gap from the ObjectIdChanged issue — even if the zone transfer is detected
correctly, the category label will be wrong.

The fix requires:
1. Add `Surveil("Surveil")` to `TransferCategory`
2. Wire `GameEvent.Surveil` to return `TransferCategory.Surveil` in
   `categoryFromEvents` (matching by forgeCardId of the moved card — but `Surveil`
   event only carries `seatId + counts`, not the cardId)
3. Alternative: emit a `GameEvent.CardSurveiled(forgeCardId)` from the engine event
   handler so `categoryFromEvents` can match it

This suggests the plan doc's fix list (items 1-5 in "What needs to happen") is
correct but incomplete — it needs a `Surveil` category label in addition to
the structural fixes.

---

## 8. AnnotationBuilder — ObjectIdChanged and ZoneTransfer builders exist (confirmed)

`AnnotationBuilder.objectIdChanged` (line 201):
```kotlin
fun objectIdChanged(origId: Int, newId: Int): AnnotationInfo = ...
    .addType(AnnotationType.ObjectIdChanged)
    .addDetails(int32Detail("orig_id", origId))
    .addDetails(int32Detail("new_id", newId))
```

`AnnotationBuilder.zoneTransfer` (line 157):
```kotlin
fun zoneTransfer(instanceId, srcZoneId, destZoneId, category, actingSeatId): AnnotationInfo = ...
    .addType(AnnotationType.ZoneTransfer_af5a)
    .addDetails(typedStringDetail("category", category))
```

Both builders exist and match the real server's field names. The `zoneTransfer`
builder takes `category` as a `String` parameter, so passing `"Surveil"` would
work once the category routing is correct.

One shape difference: real server index 247 has `affectorId:328` on both
`ObjectIdChanged` and `ZoneTransfer`. Our `objectIdChanged` builder does NOT set
`affectorId` — it has no parameter for it. The plan doc mentions
`affectorId:328 affectedIds:[172]` for `ObjectIdChanged`. This is a minor
conformance gap in the builder.

---

## Verdict

| Claim | Status |
|---|---|
| Index 247 has ObjectIdChanged orig_id:172 new_id:329 | Confirmed |
| Index 247 has ZoneTransfer zone_src:32 zone_dest:33 category:"Surveil" | Confirmed |
| Index 103 (keep) has no ZoneTransfer | Confirmed |
| Both old (172) and new (329) objects in index 247 diff | Confirmed |
| Root cause: limbo transit makes detectZoneTransfers miss the transfer | Partially confirmed — zone IS recorded via zone-list tracking, but category will be wrong (Mill vs Surveil) |
| ObjectIdChanged + ZoneTransfer builders exist | Confirmed |
| TransferCategory.Surveil missing | New finding — not in plan doc |
| objectIdChanged builder missing affectorId | New finding — minor shape gap |
