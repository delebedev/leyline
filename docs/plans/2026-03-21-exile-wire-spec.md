# Exile Wire Spec — Sheltered by Ghosts (grpId 92090)

Session: `recordings/2026-03-21_23-56-57`
Mechanic: Aura with ETB-triggered exile ("until leaves battlefield" permanent exile to UI)
Card: Sheltered by Ghosts (grpId 92090) — ward 2 + "When enchanted creature enters, exile target nonland permanent an opponent controls."
Human seat: 1
Target exiled: instanceId 302 → 312 (grpId 93813, Elf Ranger, opponent's)

---

## Instance IDs

| Role | instanceId | grpId |
|------|-----------|-------|
| Sheltered by Ghosts (aura) | 308 | 92090 |
| ETB trigger ability | 311 | 176387 |
| Enchanted creature (Legendary Cat Noble) | 295 | 87402 |
| Exiled card (Elf Ranger) pre-exile | 302 | 93813 |
| Exiled card post-exile (new iid) | 312 | 93813 |

---

## Lifecycle Summary

### Phase 1 — Cast (frame 178, gsId 128)

SbG enters the stack as instanceId 308.

```
ObjectIdChanged:  affectedIds=[163], orig_id=163, new_id=308
ZoneTransfer:     affectedIds=[308], zone_src=31(Hand), zone_dest=27(Stack), category="CastSpell"
PlayerSelectingTargets: affectorId=1, affectedIds=[308]   ← attach target selection
```

Persistent: `EnteredZoneThisTurn` on zone 27 (Stack) for iid 308.

### Phase 2 — Attach target confirmed (frame 179, gsId 130)

Client submits enchant target (creature 295). Persistent annotation carries the TargetSpec:

```
PlayerSubmittedTargets: affectorId=1, affectedIds=[308]
TargetSpec (persistent): affectorId=308, affectedIds=[295],
  details={abilityGrpId:1886, index:1, promptParameters:308, promptId:152}
```

Mana payment annotations also fire here (ManaPaid, TappedUntappedPermanent, etc.).

### Phase 3 — Resolve + Attach (frame 181, gsId 132)

SbG resolves to battlefield. Trigger fires. All in one GSM:

```
ResolutionStart:      affectorId=308, affectedIds=[308], details={grpid:92090}
ResolutionComplete:   affectorId=308, affectedIds=[308], details={grpid:92090}
LayeredEffectCreated: affectorId=308, affectedIds=[7002]   ← power modifier
LayeredEffectCreated: affectorId=308, affectedIds=[7003]   ← ability granting (ward + ETB)
ZoneTransfer:         affectorId=1,   affectedIds=[308],   zone_src=27(Stack), zone_dest=28(Battlefield), category="Resolve"
AttachmentCreated:    affectorId=308, affectedIds=[295]    ← transient (see shape note)
AbilityInstanceCreated: affectorId=308, affectedIds=[311], details={source_zone:28}
PlayerSelectingTargets: affectorId=1, affectedIds=[311]   ← exile target selection on trigger
```

Persistent annotations created:
```
EnteredZoneThisTurn (id=5):  affectorId=28(Battlefield), affectedIds=[308,307]
ModifiedPower|LayeredEffect: affectorId=308, affectedIds=[295], details={effect_id:7002}
AddAbility|LayeredEffect:    affectorId=308, affectedIds=[295],
  details={originalAbilityObjectZcid:[308,308], UniqueAbilityId:[230,231],
           grpid:[12,141939], effect_id:7003}
Attachment (id=354):         affectorId=308, affectedIds=[295]    ← persistent (see shape note)
TriggeringObject (id=357):   affectorId=311, affectedIds=[308], details={source_zone:28}
```

Deleted persistent: ids [332, 352, 4] (TargetSpec for enchant attach, old EnteredZoneThisTurn).

### Phase 4 — Exile target chosen (frame 186, gsId 134)

No `SelectNReq` — the server uses `PlayerSelectingTargets` (already in frame 181) and awaits
the client's `PerformActionResp` (C→S file 000000181_MD_C-S_DATA.bin). The GSM for "submitted" carries:

```
PlayerSubmittedTargets: affectorId=1, affectedIds=[311]
TargetSpec (persistent, id=359): affectorId=311, affectedIds=[302],
  details={abilityGrpId:176387, index:1, promptParameters:311, promptId:1330}
```

### Phase 5 — Exile resolves (frame 189, gsId 136)

```
ResolutionStart:    affectorId=311, affectedIds=[311], details={grpid:176387}
ObjectIdChanged:    affectorId=311, affectedIds=[302], orig_id=302, new_id=312
ZoneTransfer:       affectorId=311, affectedIds=[312],
                    zone_src=28(Battlefield), zone_dest=29(Exile), category="Exile"
ResolutionComplete: affectorId=311, affectedIds=[311], details={grpid:176387}
AbilityInstanceDeleted: affectorId=308, affectedIds=[311]
```

Persistent created:
```
EnteredZoneThisTurn (id=6): affectorId=29(Exile), affectedIds=[312]
DisplayCardUnderCard (id=364): affectorId=308, affectedIds=[312],
  details={TemporaryZoneTransfer:1, Disable:0}
```

Deleted persistent: id [359] (TargetSpec for exile target).
diffDeletedInstanceIds: [311] (trigger ability iid cleaned up).

Zones in this GSM:
```
Stack(27): objectIds=[]
Battlefield(28): objectIds=[308,307,301,295,294,291,289,285,284,281,279]   ← 302 gone
Exile(29): objectIds=[312]
Limbo(30): objectIds=[302,163,…]   ← old iid 302 in limbo alongside 312
```

---

## Key Wire Facts

### ZoneTransfer category for exile
`"Exile"` — confirmed. Matches `TransferCategory.Exile` in our code.

### ObjectIdChanged before ZoneTransfer
Exile follows the same pattern as Mill/Destroy: `ObjectIdChanged` fires first (302→312),
then `ZoneTransfer` uses the **new** id (312). affectorId on both = trigger ability iid (311).

### No "until leaves battlefield" return annotation
There is no conditional-return annotation in this recording. The exile is treated as
permanent from the wire's perspective. The return condition ("until SbG leaves") is
encoded only in the `DisplayCardUnderCard` annotation with `TemporaryZoneTransfer:1`.
When SbG leaves the battlefield, a separate GSM would include the return ZoneTransfer.

### DisplayCardUnderCard — "tuck" annotation
```json
{"id":364,"types":["DisplayCardUnderCard"],"affectorId":308,"affectedIds":[312],
 "details":{"TemporaryZoneTransfer":1,"Disable":0}}
```
- `affectorId` = the aura (308) — not present in our current `displayCardUnderCard()` builder
- `affectedIds` = the exiled card's **new** iid (312)
- `TemporaryZoneTransfer:1` = this exile is conditional / will return
- Persistent for the duration SbG remains on battlefield

### AttachmentCreated shape discrepancy
Wire:
```json
{"id":355,"types":["AttachmentCreated"],"affectorId":308,"affectedIds":[295]}
```
Our builder comment says "no affectorId, affectedIds=[aura, target]" — **WRONG.**
Real shape: `affectorId=auraIid`, `affectedIds=[targetIid]` (target only, not both).

### Attachment (persistent) shape discrepancy
Wire:
```json
{"id":354,"types":["Attachment"],"affectorId":308,"affectedIds":[295]}
```
Same issue — `affectorId=auraIid`, `affectedIds=[targetIid]` only.

### ETB trigger targeting — PlayerSelectingTargets not SelectNReq
The server uses `PlayerSelectingTargets` (in the resolution GSM) for triggered ability
target selection. There is no `SelectNReq` message. The client submits via `PerformActionResp`
and the confirmation arrives as `PlayerSubmittedTargets` + persistent `TargetSpec`.

---

## Code Gaps (flag for engine-bridge agent)

### 1. AttachmentCreated/Attachment affectorId missing
`AnnotationBuilder.attachmentCreated()` and `attachment()` both omit `affectorId`.
Wire confirms it should be `affectorId=auraIid`.
Also `affectedIds` should be `[targetIid]` only, not `[auraIid, targetIid]`.

File: `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt` lines 405–420.

### 2. DisplayCardUnderCard missing affectorId
`AnnotationBuilder.displayCardUnderCard()` builds with no affectorId.
Wire shows `affectorId=auraIid` (the enchantment causing the exile).

File: `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt` lines 701–707.

### 3. DisplayCardUnderCard not emitted for aura-triggered exile
No code path currently emits `DisplayCardUnderCard` for the exile-under-aura pattern.
This is needed for the client to show the "tucked" card under the aura artwork.

### 4. Exile category — looks correct
`TransferCategory.Exile("Exile")` matches wire string `"Exile"`. No bug here.

### 5. Ward — not tested via wire
Ward 2 is on SbG but the opponent did not attempt to target the enchanted creature in this
session, so no ward trigger fired. Ward wire shape remains unconfirmed from recordings.

---

## Annotation Sequence Summary (exile resolution, gsId 136)

```
transient:  ResolutionStart(311)
transient:  ObjectIdChanged(302→312, affectorId=311)
transient:  ZoneTransfer(312, src=Battlefield, dest=Exile, cat="Exile", affectorId=311)
transient:  ResolutionComplete(311)
transient:  AbilityInstanceDeleted(311, affectorId=308)
persistent: EnteredZoneThisTurn(zone=29, affected=[312])
persistent: DisplayCardUnderCard(affectorId=308, affected=[312], TemporaryZoneTransfer=1, Disable=0)
```

---

## Catalog Impact

- `exile` mechanic: first wire data confirmed. ZoneTransfer category="Exile" is correct.
- `aura-etb-exile` pattern: new sub-mechanic. DisplayCardUnderCard "tuck" annotation required.
- `ward`: still partial — no trigger observed in this session.
