# Stasis Snare Wire Spec (grpId 94009)

Session: `recordings/2026-03-22_00-07-34`
Mechanic: Standalone enchantment with ETB-triggered exile ("until Stasis Snare leaves the battlefield")
Card: Stasis Snare (grpId 94009) — Flash enchantment, "When Stasis Snare enters, exile target creature an opponent controls until Stasis Snare leaves the battlefield."
Human seat: 1
Target exiled: instanceId 303 → 313 (grpId 75500, 2/2 Zombie, opponent's)

**Key difference from Sheltered by Ghosts:** Stasis Snare is NOT an aura. No `AttachmentCreated`, no `Attachment` persistent, no `LayeredEffectCreated`. The exile pattern is otherwise nearly identical.

---

## Instance IDs

| Role | instanceId | grpId |
|------|-----------|-------|
| Stasis Snare (in hand) | 298 | 94009 |
| Stasis Snare (stack/battlefield, post-ObjectIdChanged) | 308 | 94009 |
| ETB trigger ability | 312 | 20262 |
| Exiled creature (Zombie) pre-exile | 303 | 75500 |
| Exiled creature post-exile (new iid) | 313 | 75500 |

---

## Lifecycle

### Phase 1 — Cast (gsId 136, index 166)

Stasis Snare cast during seat 1's own Main1, turn 7. Flash NOT used here (own main phase).

```
ObjectIdChanged:  affectedIds=[298], orig_id=298, new_id=308   ← no affectorId
ZoneTransfer:     affectedIds=[308], zone_src=31(Hand), zone_dest=27(Stack), category="CastSpell"   ← no affectorId
ManaPaid×3:       affectorId=<land iids>, affectedIds=[308], colors=[1,1,1]   ← 1WW cost
UserActionTaken:  affectorId=1, affectedIds=[308], actionType=1, abilityGrpId=0
```

Note: Both `ObjectIdChanged` and the `CastSpell` `ZoneTransfer` carry **no affectorId** (same as SbG aura case).

Persistent: `EnteredZoneThisTurn(id=4, affectorId=27, affectedIds=[308])`

No `PlayerSelectingTargets` here — enchantments don't need a cast target. Stasis Snare goes onto stack without targeting until the ETB trigger fires.

### Phase 2 — Resolve (gsId 138, index 168)

Stasis Snare resolves to battlefield. ETB trigger fires in the **same GSM**.

```
ResolutionStart:          affectorId=308, affectedIds=[308], details={grpid:94009}
ResolutionComplete:       affectorId=308, affectedIds=[308], details={grpid:94009}
ZoneTransfer:             affectorId=1,   affectedIds=[308], zone_src=27(Stack), zone_dest=28(Battlefield), category="Resolve"
AbilityInstanceCreated:   affectorId=308, affectedIds=[312], details={source_zone:28}
PlayerSelectingTargets:   affectorId=1,   affectedIds=[312]   ← exile target selection begins
```

Persistent created:
```
EnteredZoneThisTurn (id=5):  affectorId=28(Battlefield), affectedIds=[308]
TriggeringObject (id=308):   affectorId=312, affectedIds=[308], details={source_zone:28}
```

Deleted persistent: [4] (stack EnteredZoneThisTurn).

**No LayeredEffectCreated. No AttachmentCreated. No Attachment.** — Non-aura enchantments emit none of these.

### Phase 3 — Target selection (gsId 138 → 140)

`SelectTargetsReq` with `sourceId=312`, `abilityGrpId=20262`, single target group, two legal targets:

```json
{
  "targetIdx": 1,
  "targets": [
    {"targetInstanceId": 290, "legalAction": "Select_a1ad", "highlight": "Hot"},
    {"targetInstanceId": 303, "legalAction": "Select_a1ad", "highlight": "Hot"}
  ],
  "minTargets": 1, "maxTargets": 1,
  "prompt": {"promptId": 1014, "parameters": [{"parameterName": "CardId", "type": "Number", "numberValue": 308}]},
  "targetingAbilityGrpId": 20262,
  "targetingPlayer": 1
}
```

Client submits `SelectTargetsResp → {targetIdx:1, targetInstanceIds:[303]}`, then `SubmitTargetsReq`.

Confirmation GSM (gsId 140, index 174):

```
PlayerSubmittedTargets (transient): affectorId=1, affectedIds=[312]
TargetSpec (persistent, id=310):    affectorId=312, affectedIds=[303],
  details={abilityGrpId:20262, index:1, promptParameters:312, promptId:1014}
```

### Phase 4 — Exile resolves (gsId 142, index 176)

```
ResolutionStart:          affectorId=312, affectedIds=[312], details={grpid:20262}
ObjectIdChanged:          affectorId=312, affectedIds=[303], orig_id=303, new_id=313
ZoneTransfer:             affectorId=312, affectedIds=[313], zone_src=28(Battlefield), zone_dest=29(Exile), category="Exile"
ResolutionComplete:       affectorId=312, affectedIds=[312], details={grpid:20262}
AbilityInstanceDeleted:   affectorId=308, affectedIds=[312]
```

Persistent created:
```
EnteredZoneThisTurn (id=6): affectorId=29(Exile), affectedIds=[313]
DisplayCardUnderCard (id=315): affectorId=308, affectedIds=[313],
  details={TemporaryZoneTransfer:1, Disable:0}
```

Deleted persistent: [310] (TargetSpec for exile target).
diffDeletedInstanceIds: [312] (trigger ability cleaned up).

Zones in this GSM:
```
Stack(27):     objectIds=[]
Battlefield(28): objectIds=[308,302,294,290,289,285,284,282,279]   ← 303 gone, 308 stays
Exile(29):     objectIds=[313]
Limbo(30):     objectIds=[303,298,…]   ← old iid 303 in limbo
```

### Phase 5 — Return from exile

**Not observed in this session.** The recording ends (gsId 363) with 308 still on the battlefield and 313 still in exile. No Murder or enchantment removal occurred. The return sequence remains unconfirmed for Stasis Snare specifically.

The return pattern is expected to follow the same convention established for SbG's non-aura-exile: when 308 is destroyed, a new GSM should fire `ZoneTransfer(308 → zone_dest=33, category="Destroy")` plus `ZoneTransfer(313 → zone_dest=28, category=<return>)` and `diffDeletedPersistentAnnotationIds=[315]` (removing DisplayCardUnderCard).

---

## Comparison: Stasis Snare vs Sheltered by Ghosts

| Property | Stasis Snare (94009) | Sheltered by Ghosts (92090) |
|----------|---------------------|----------------------------|
| Card type | Enchantment (not Aura) | Enchantment — Aura |
| AttachmentCreated | **ABSENT** | Present (transient) |
| Attachment (persistent) | **ABSENT** | Present (persistent) |
| LayeredEffectCreated | **ABSENT** | 2× (power mod + ability grant) |
| PlayerSelectingTargets at cast | **ABSENT** | Present (for enchant target) |
| PlayerSelectingTargets at resolve | Present (for exile target) | Present (for exile target) |
| ETB trigger iid | 312 (grpId 20262) | 311 (grpId 176387) |
| SelectTargetsReq for ETB | Yes — standard handshake | Yes — standard handshake |
| ObjectIdChanged before ZoneTransfer | Yes (affectorId=triggerIid) | Yes (affectorId=triggerIid) |
| ZoneTransfer category | `"Exile"` | `"Exile"` |
| DisplayCardUnderCard affectorId | **308 (Stasis Snare iid)** | 308 (SbG aura iid) |
| DisplayCardUnderCard details | `{TemporaryZoneTransfer:1, Disable:0}` | `{TemporaryZoneTransfer:1, Disable:0}` |
| TriggeringObject affectorId | triggerIid → enchantment iid | triggerIid → aura iid |

**Core finding:** The exile-resolution annotation pattern is **identical** between aura-exile and non-aura-exile. `DisplayCardUnderCard.affectorId` always points to the **enchantment iid** (308 in both cases) regardless of whether it is an aura. The difference is purely in what precedes the exile trigger — auras emit Attachment/LayeredEffect on resolve; standalone enchantments do not.

---

## Key Wire Facts

### No AttachmentCreated for non-aura enchantments
`AttachmentCreated` and `Attachment` are strictly for Aura cards that attach to a permanent. Stasis Snare emits neither. The relationship between Stasis Snare and the exiled card is expressed entirely via `DisplayCardUnderCard`.

### DisplayCardUnderCard affectorId = enchantment's iid
`affectorId=308` in both sessions. This is the **on-battlefield** instance of the enchantment (post-ObjectIdChanged from the cast). Same shape for aura and non-aura.

### ObjectIdChanged affectorId null/absent on cast
Both `ObjectIdChanged(298→308)` and `ZoneTransfer(CastSpell)` carry no `affectorId`. This appears to be the pattern for player-initiated casts (not ability-triggered zone transfers, which do have an affectorId).

### SelectTargetsReq for ETB trigger
Server uses `SelectTargetsReq`/`SelectTargetsResp`/`SubmitTargetsReq` handshake (not `SelectNReq`) for targeted ETB triggers. `sourceId` = trigger ability iid. `abilityGrpId` = trigger's grpId. Same shape as SbG.

### Flash timing — not tested
Stasis Snare was cast during own main phase. Flash-on-opponent-turn protocol behavior (if any different) is unconfirmed from this recording.

---

## Code Gaps (flag for engine-bridge agent)

### 1. DisplayCardUnderCard missing affectorId (carried over from SbG)
`AnnotationBuilder.displayCardUnderCard()` emits no `affectorId`.
Wire: `affectorId=<enchantmentIid>` for both aura-exile and standalone-exile cases.
Fix: pass enchantment iid as affectorId to `displayCardUnderCard()` builder.

### 2. No code path emits DisplayCardUnderCard for non-aura-ETB-exile
The exile-under-enchantment pattern needs `DisplayCardUnderCard` emitted on the trigger resolution GSM. Whether existing code handles this for non-aura enchantments is unconfirmed — needs check.

### 3. Return-from-exile unconfirmed
When Stasis Snare leaves the battlefield, the server should:
- `ZoneTransfer` the enchantment (Destroy/SBA category)
- `ZoneTransfer` the exiled creature back to battlefield (likely `category="ReturnToPlay"` or similar)
- Delete `DisplayCardUnderCard` pann (id=315 in this session)
Category string for the return transfer is unobserved. Needs a recording where Stasis Snare is actually destroyed.

---

## Annotation Sequence Summary (exile resolution, gsId 142)

```
transient:  ResolutionStart(312, grpid=20262)
transient:  ObjectIdChanged(303→313, affectorId=312)
transient:  ZoneTransfer(313, src=Battlefield, dest=Exile, cat="Exile", affectorId=312)
transient:  ResolutionComplete(312, grpid=20262)
transient:  AbilityInstanceDeleted(312, affectorId=308)
persistent: EnteredZoneThisTurn(zone=29, affected=[313])
persistent: DisplayCardUnderCard(affectorId=308, affected=[313], TemporaryZoneTransfer=1, Disable=0)
```

---

## Catalog Impact

- `exile` mechanic: confirmed for non-aura enchantment. ZoneTransfer category="Exile" consistent.
- `exile-under-enchantment` pattern: `DisplayCardUnderCard` required for both aura and non-aura.
- `flash`: not tested (cast on own main phase). No protocol difference detected.
- `stasis-snare` specifically: fully scoped except return-from-exile sequence.
