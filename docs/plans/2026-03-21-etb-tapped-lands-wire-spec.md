# ETB-Tapped Lands Wire Spec

Sourced from two sessions (2026-03-21):
- `recordings/2026-03-21_22-31-46/` — Rakdos Guildgate (grpId 94121), Bloodfell Caves (grpId 93972)
- `recordings/2026-03-21_22-31-46-game3/` — Temple of Malady (grpId 94128), Jungle Hollow (grpId 93976), Golgari Guildgate (grpId 94117)

---

## 1. Tapped state: how it arrives

**The object arrives on the battlefield already tapped. No TappedUntappedPermanent annotation on ETB.**

The `isTapped: true` flag is set directly on the `GameObjectInfo` in the same Diff GSM that carries the `ZoneTransfer` annotation. There is no separate annotation marking the tapping as a replacement effect — the client reads the initial tapped state from the object proto field.

Evidence: in every ETB-tapped land observation, the object appears in `objects[]` with `isTapped: true` in the same gsId as the `ZoneTransfer` annotation:

```
gsId=24  ZoneTransfer(affectedIds=[281], zone_src=31→28, category=PlayLand)
         GameObjectInfo(instanceId=281, grpId=94121, isTapped=true, zoneId=28)
```

**No `ReplacementEffect` (62) or `ReplacementEffectApplied` (77) annotation appears for ETB-tapped.**

---

## 2. Plain dual-land / guildgate sequence (no ETB trigger)

Cards: Rakdos Guildgate (281), Golgari Guildgate (349)

All annotations fire in a single Diff GSM:

```
ObjectIdChanged(affectedIds=[newId], orig_id=handId, new_id=battlefieldId)   [if id changed]
ZoneTransfer(affectedIds=[newId], zone_src=31, zone_dest=28, category=PlayLand)
UserActionTaken(affectorId=seatId, affectedIds=[newId], actionType=3, abilityGrpId=0)
```

Object arrives `isTapped: true`. **No further annotations.** No lifegain. No trigger.

Note: Golgari Guildgate (line 719 game3) shows `ObjectIdChanged` firing when the hand instanceId (348) is reallocated to a new battlefield instanceId (349). This is the standard id-change pattern.

---

## 3. ETB-lifegain dual-land sequence (Bloodfell Caves, Jungle Hollow)

Cards: Bloodfell Caves (93972), Jungle Hollow (93976) — both gain 1 life on ETB.

**Two separate Diff GSMs:**

### GSM 1 (SendHiFi) — land arrives
```
ZoneTransfer(affectedIds=[313], zone_src=31, zone_dest=28, category=PlayLand)
UserActionTaken(affectorId=1, affectedIds=[313], actionType=3, abilityGrpId=0)
AbilityInstanceCreated(affectorId=313, affectedIds=[314], details={source_zone: 28})
```
Object: `isTapped: true`, `zoneId: 28`.

### GSM 2 (SendAndRecord) — trigger resolves
```
ModifiedLife(affectorId=314, affectedIds=[1], details={life: 1})
AbilityInstanceDeleted(affectorId=313, affectedIds=[314])
```

Pattern: the ETB lifegain trigger fires as `AbilityInstanceCreated` in the arrival diff, resolves in the next diff with `ModifiedLife` (type 10), then `AbilityInstanceDeleted` cleans up the stack ability. The trigger's affectedId in player context is the seatId integer (1 or 2), matching how ModifiedLife works elsewhere.

Key: `AbilityInstanceCreated.affectorId` = land instanceId; `affectedIds` = new ability-stack instanceId. `AbilityInstanceDeleted.affectorId` = land instanceId; `affectedIds` = same ability-stack instanceId.

---

## 4. ETB-scry sequence (Temple of Malady)

Card: Temple of Malady (94128, grpId) — ETB tapped, scry 1.

**Three-phase sequence across multiple GSMs:**

### Phase A (SendHiFi) — land arrives
```
ZoneTransfer(affectedIds=[279], zone_src=31, zone_dest=28, category=PlayLand)
UserActionTaken(affectorId=1, affectedIds=[279], actionType=3, abilityGrpId=0)
AbilityInstanceCreated(affectorId=279, affectedIds=[280], details={source_zone: 28})
```
Object: `isTapped: true`, `zoneId: 28`.

### Phase B (Send, updateType=Send) — scry resolution starts
```
ResolutionStart(affectorId=280, affectedIds=[280], details={grpid: 176406})
MultistepEffectStarted(affectorId=280, affectedIds=[1], details={SubCategory: 15})
```
Simultaneously, a `GroupReq` (GRE prompt type) is sent for scry:
```
GroupReq {
  instanceIds: [166],            // card being scryed
  groupSpecs: [
    {upperBound: 1, zoneType: Library, subZoneType: Top},
    {upperBound: 1, zoneType: Library, subZoneType: Bottom}
  ],
  groupType: Ordered,
  context: "Scry",
  sourceId: 280                  // the ETB ability instance
}
```
Client responds with `GroupResp` indicating the scry decision (top/bottom).

### Phase C (SendHiFi) — scry result
```
Scry(affectorId=280, affectedIds=[166], details={topIds: ?, bottomIds: 166})
MultistepEffectComplete(affectorId=280, affectedIds=[1], details={SubCategory: 15})
ResolutionComplete(affectorId=280, affectedIds=[280], details={grpid: 176406})
AbilityInstanceDeleted(affectorId=279, affectedIds=[280])
```

#### Scry annotation detail keys
- `topIds` — instanceIds of cards placed on top (may be absent/empty)
- `bottomIds` — instanceIds of cards placed on bottom (may be absent/empty)
- Both are populated from the client's GroupResp decision
- `affectorId` = the ETB ability stack instance (not the land itself)
- `affectedIds` = the scryed card's instanceId

SubCategory 15 = Scry multistep effect category.

**The Scry prompt is a `GroupReq` not a `SelectNreq`.** The existing scry implementation must handle `GroupReq` / `GroupResp` round-trip, not just fire the annotation.

---

## 5. What does NOT appear

| Expectation | Observed |
|---|---|
| `TappedUntappedPermanent` on land ETB | Absent — tapped state is on the object, not an annotation |
| `ReplacementEffect` (62) | Absent |
| `ReplacementEffectApplied` (77) | Absent |
| `TappedUntappedPermanent` for lifegain/scry land tap | Absent — only the object field carries it |

`TappedUntappedPermanent` (type 4) only appears when a land is **tapped for mana** (via `ActivateMana`) or via a non-ETB tap effect. It is not used to signal ETB-tapped state.

---

## 6. Current code assessment

### What works
- Object arrives with `isTapped: true` — `ObjectMapper` reads `card.isTapped` directly (line 124). Forge sets ETB-tapped cards as tapped at ETB time, so this is automatically correct.
- `ZoneTransfer` + `UserActionTaken` for PlayLand — wired in `annotationsForTransfer`.
- `Scry` annotation (type 65) — implemented.
- `ModifiedLife` (type 10) — implemented.

### Gaps

**1. ETB lifegain trigger not modelled**

Current `PlayLand` path in `annotationsForTransfer` emits only:
```
ObjectIdChanged / ZoneTransfer / UserActionTaken
```
No `AbilityInstanceCreated` for the ETB trigger. Bloodfell Caves / Jungle Hollow lifegain fires a trigger that must appear as `AbilityInstanceCreated` in the arrival GSM, then `ModifiedLife` + `AbilityInstanceDeleted` in the follow-on GSM.

Code: `AnnotationPipeline.annotationsForTransfer()`, `TransferCategory.PlayLand` branch (~line 272). The lifegain `ModifiedLife` is handled by `GameEvent.LifeChanged` → `mechanicAnnotations()` but without the trigger wrapper (AIC/AID), which will produce a floating ModifiedLife with no ability context.

**2. Scry prompt is GroupReq, not SelectNreq**

The Temple of Malady scry fires a `GroupReq` round-trip (Phase B above). Current `MatchSession.onSelectN` handles `SelectNresp`. Scry requires a `GroupReq` sender and `GroupResp` handler. This is a separate GRE message type not currently wired.

**3. ETB trigger AbilityInstanceCreated instanceId allocation**

Ability stack instanceIds for ETB triggers (e.g. `314` for Bloodfell's trigger) must be allocated consistently. Currently `STACK_ABILITY_ID_OFFSET` covers abilities cast by the player; ETB trigger abilities need their own allocation strategy or a Forge-event-driven approach.

**4. MultistepEffectStarted / MultistepEffectComplete (83/84)**

These wrap the scry resolution and are MISSING per rosetta.md. Required for Temple of Malady conformance.

---

## 7. Conformance summary by card

| Card | grpId | ETB tapped | Trigger | Conformant? | Gap |
|------|-------|-----------|---------|-------------|-----|
| Rakdos Guildgate | 94121 | yes | none | yes (object tapped, annotations correct) | — |
| Golgari Guildgate | 94117 | yes | none | yes | — |
| Bloodfell Caves | 93972 | yes | lifegain 1 | partial | AIC/AID wrapper missing for trigger |
| Jungle Hollow | 93976 | yes | lifegain 1 | partial | same |
| Temple of Malady | 94128 | yes | scry 1 | partial | GroupReq not sent; MultistepEffect missing |

---

## 8. Engine flag path (for engine-bridge agent)

ETB-tapped state in Forge: `Card.isTapped()` returns `true` after the game processes the replacement effect. No explicit event — Forge applies the ETB-tapped replacement before `GameEventLandPlayed` fires. The tapped state is already set when `ObjectMapper.buildCard()` reads `card.isTapped`.

ETB triggers (lifegain, scry) fire as triggered abilities on Forge's stack. `GameEventSpellAbilityCast` fires when the trigger goes on the stack. The `SpellAbilityStackInstance` has a reference back to the source land. Flag for engine-bridge: need to wire `GameEventSpellAbilityCast` when the trigger source is an ETB (not a player cast) to emit `AbilityInstanceCreated` with the land instanceId as affector and a new ability-stack instanceId as affected.
