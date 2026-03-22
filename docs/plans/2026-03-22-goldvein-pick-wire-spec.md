# Goldvein Pick Wire Spec

**Recording:** `recordings/2026-03-22_00-07-34/md-frames.jsonl`
**Card:** Goldvein Pick — grpId 93966 — Equipment. Equip {1}. Equipped creature gets +1/+1. Whenever equipped creature deals combat damage to a player, create a Treasure token.
**Seat:** 1 (human)

---

## 1. Instance Identity

- **Hand iid:** 163 (grpId 93966, zoneId 31, gsId 2)
- **Cast:** ObjectIdChanged `{orig_id:163, new_id:320}` → 320 goes to Stack (zoneId 27), then resolves to Battlefield (zoneId 28)

---

## 2. Casting

**gsId 191** — PerformActionResp: `{type:Cast, instanceId:163, grpId:93966}`

**gsId 191 GSM annotations:**
```
ObjectIdChanged   affectedIds:[163]  details:{orig_id:163, new_id:320}
ZoneTransfer      affectedIds:[320]  details:{zone_src:31, zone_dest:27, category:"CastSpell"}
UserActionTaken   affectorId:1       affectedIds:[320]  details:{actionType:1, abilityGrpId:0}
```
Plus two mana payment cycles (White from Plains iid 284, White from Gate iid 279) — same structure as any spell cast.

**gsId 193 GSM — resolution:**
```
ResolutionStart   affectorId:320  affectedIds:[320]  details:{grpid:93966}
ResolutionComplete affectorId:320  affectedIds:[320]  details:{grpid:93966}
LayeredEffectCreated affectorId:320  affectedIds:[7002]
ZoneTransfer      affectorId:1    affectedIds:[320]  details:{zone_src:27, zone_dest:28, category:"Resolve"}
```
- `LayeredEffectCreated` fires at ETB — effect_id 7002 is the +1/+1 static buff. No target yet (not attached).
- Actions list after resolution includes `{type:Activate, instanceId:320, seatId:1, abilityGrpId:1268}` — equip ability now available.

---

## 3. Equip Activation — Full Wire Sequence (Turn 9, gsId 193–198)

**Step 1 — Client activates:**
PerformActionResp: `{type:Activate, instanceId:320, grpId:93966, abilityGrpId:1268}`

**Step 2 — GSM gsId 194 — ability instance pushed to stack:**
```
AbilityInstanceCreated  affectorId:320  affectedIds:[323]  details:{source_zone:28}
PlayerSelectingTargets  affectorId:1    affectedIds:[323]
```
Object: `instanceId:323  grpId:1268  zoneId:27  type:Ability  owner:1  controller:1`

Note: grpId of the ability instance on stack is **1268** (equip ability grpId), not the card's grpId.

**Step 3 — SelectTargetsReq (gsId 194):**
```json
{
  "targets": [{
    "targetIdx": 1,
    "targets": [{"targetInstanceId": 285, "legalAction": "Select_a1ad", "highlight": "Hot"}],
    "minTargets": 1, "maxTargets": 1,
    "prompt": {"promptId": 1010, "parameters": [{"parameterName": "CardId", "type": "Number", "numberValue": 320}]},
    "targetingAbilityGrpId": 1268,
    "targetingPlayer": 1
  }],
  "sourceId": 323,
  "abilityGrpId": 1268
}
```
- `sourceId` = ability instance iid (323)
- `abilityGrpId` = 1268 (equip ability)
- `promptId: 1010` — "choose a creature to equip"
- Second SelectTargetsReq follows with `legalAction:"Unselect"` as confirmed selection (same two-step pattern as ward/fight targeting)
- Client responds: `SelectTargetsResp {targetIdx:1, targetInstanceIds:[285]}`
- Client submits: `SubmitTargetsReq`

**Step 4 — GSM gsId 196 — mana paid, TargetSpec set:**
```
PlayerSubmittedTargets  affectorId:1   affectedIds:[323]
AbilityInstanceCreated  affectorId:294  affectedIds:[324]   details:{source_zone:28}   (Gate tapping)
TappedUntappedPermanent affectorId:324  affectedIds:[294]   details:{tapped:1}
UserActionTaken         affectorId:1    affectedIds:[324]   details:{actionType:4, abilityGrpId:1209}
ManaPaid                affectorId:294  affectedIds:[323]   details:{id:367, color:1}
AbilityInstanceDeleted  affectorId:294  affectedIds:[324]
UserActionTaken         affectorId:1    affectedIds:[323]   details:{actionType:2, abilityGrpId:1268}
```
Persistent: `TargetSpec  affectorId:323  affectedIds:[285]  details:{abilityGrpId:1268, index:1, promptParameters:323, promptId:1010}`

Note: `actionType:2` = ability activation confirmed (vs `actionType:1` = cast, `actionType:4` = mana ability).

No `PayCostsReq` for the equip cost — mana is paid inline via UserActionTaken/ManaPaid annotations in the same GSM. The equip cost `{1}` = 1 colorless = tapping Gate (any-color mana source).

**Step 5 — GSM gsId 198 — equip resolves:**
```
ResolutionStart   affectorId:323  affectedIds:[323]  details:{grpid:1268}
AttachmentCreated affectorId:320  affectedIds:[285]
ResolutionComplete affectorId:323  affectedIds:[323]  details:{grpid:1268}
AbilityInstanceDeleted affectorId:320  affectedIds:[323]
```
Persistent annotations added:
```
id:403  types:["ModifiedToughness","ModifiedPower","LayeredEffect"]  affectorId:320  affectedIds:[285]  details:{effect_id:7002}
id:418  types:["Attachment"]  affectorId:320  affectedIds:[285]
```
Object update: `instanceId:285  power:2  toughness:4` (was 1/3 → +1/+1 applied)

---

## 4. Equipment Attachment Shape (vs Aura)

### Equipment (Goldvein Pick → Angel, gsId 198):
**Transient:**
```
AttachmentCreated  affectorId:320 (equipment)  affectedIds:[285] (host creature)
```
**Persistent:**
```
Attachment         affectorId:320 (equipment)  affectedIds:[285] (host creature)
ModifiedToughness + ModifiedPower + LayeredEffect  affectorId:320  affectedIds:[285]  details:{effect_id:7002}
```

### Aura (Sheltered by Ghosts, from 2026-03-21 session):
**Transient:**
```
AttachmentCreated  affectorId:auraIid  affectedIds:[targetIid]
```
**Persistent:**
```
Attachment         affectorId:auraIid  affectedIds:[targetIid]
```
Plus `DisplayCardUnderCard` for the exile subeffect.

**Shape is identical** for equipment vs aura: `affectorId=permanentIid, affectedIds=[hostIid]`. The earlier note in `exile-aura-wire.md` ("no affectorId, affectedIds=[aura,target]") is wrong — wire has affectorId in both cases.

---

## 5. Re-equip to Different Creature (Turn 13, gsId 318–323)

After the equipped Angel (285) was destroyed by an instant in turn 10, persistent annotations `id:403` (`ModifiedToughness/ModifiedPower/LayeredEffect`) and `id:418` (`Attachment`) were both deleted in `diffDeletedPersistentAnnotationIds` of the GSM where the Angel died. The equipment (320) itself stays on battlefield — only the Attachment pAnn is removed.

Re-equip to Turtle (327) in turn 13:
- Same activate → SelectTargetsReq → SubmitTargetsReq flow, with ability instance iid 357
- Resolution GSM (gsId 323):
  ```
  AttachmentCreated  affectorId:320  affectedIds:[327]
  ```
  Persistent:
  ```
  id:403  types:["ModifiedToughness","ModifiedPower","LayeredEffect"]  affectorId:320  affectedIds:[327]  details:{effect_id:7002}
  id:690  types:["Attachment"]  affectorId:320  affectedIds:[327]
  ```
  Note: `id:403` persists from the first attach — same pAnn id is **updated** (affectedIds changes from [285] to [327]) rather than deleted+recreated. New `Attachment` pAnn gets a fresh id (690 instead of 418).

Object update: `instanceId:327  power:1  toughness:6` (Turtle base 0/5 → +1/+1)

---

## 6. PowerToughnessModCreated

No separate `PowerToughnessModCreated` annotation type observed. The +1/+1 buff is encoded as:
- `LayeredEffectCreated` at card ETB (effect_id 7002)
- Persistent `["ModifiedToughness","ModifiedPower","LayeredEffect"]` pAnn once attached, with the same effect_id
- The creature object's `power`/`toughness` fields update numerically in the same GSM as attachment resolution

No standalone `PowerToughnessModCreated` message emitted.

---

## 7. Treasure Token Creation Trigger

### Trigger firing (Turn 9, gsId 208)

Equipped Angel (285) attacked unblocked, dealt 2 combat damage to player 2.

**GSM gsId 208:**
```
PhaseOrStepModified  affectedIds:[1]  details:{phase:3, step:7}       (CombatDamage step)
DamageDealt          affectorId:285   affectedIds:[2]  details:{damage:2, type:1, markDamage:1}
AbilityInstanceCreated affectorId:320  affectedIds:[325]  details:{source_zone:28}
SyntheticEvent       affectorId:285   affectedIds:[2]   details:{type:1}
ModifiedLife         affectorId:285   affectedIds:[2]   details:{life:-2}
```
Persistent: `TriggeringObject  affectorId:325  affectedIds:[285]  details:{source_zone:28}`

Object on stack: `instanceId:325  grpId:116799  zoneId:27  type:Ability  owner:1  controller:1`

Key points:
- Ability instance (325) is created on stack by `affectorId:320` (equipment, not the creature)
- grpId of the triggered ability instance = **116799** (this is the "deal combat damage → create Treasure" triggered ability's grpId — separate from the equip ability grpId 1268)
- `TriggeringObject` pAnn: `affectorId=abilityInstanceIid(325), affectedIds=[creatureIid(285)]` — points to the creature that triggered it
- `DamageDealt` and `AbilityInstanceCreated` in same GSM batch

### Resolution (gsId 210)

**GSM gsId 210:**
```
ResolutionStart   affectorId:325  affectedIds:[325]  details:{grpid:116799}
TokenCreated      affectorId:325  affectedIds:[326]
ResolutionComplete affectorId:325  affectedIds:[325]  details:{grpid:116799}
AbilityInstanceDeleted affectorId:320  affectedIds:[325]
```
Object added: `instanceId:326  grpId:94178  zoneId:28  type:Token  owner:1  controller:1  cardTypes:["Artifact"]  subtypes:["Treasure"]  uniqueAbilityCount:1`

**Treasure token grpId = 94178**

- `TokenCreated` affectorId = ability instance (325), not the equipment (320)
- `AbilityInstanceDeleted` affectorId = equipment (320) — the equipment is the source, the iid is the ability. Consistent with `AbilityInstanceCreated` pairing.
- No `TriggeringObject` pAnn deletion needed — it's in `diffDeletedPersistentAnnotationIds:[429]`

### Second occurrence (Turn 13, gsId 333)

Turtle (327, now equipped) attacks unblocked, deals 1 damage to player 2. Same pattern:
- Ability instance 359, grpId 116799
- Treasure token 360, grpId 94178

---

## 8. Treasure Sacrifice (PayCostsReq + Sacrifice Flow)

After Treasure token 326 lands, it appears in ActionsAvailableReq:
`{type:ActivateMana, instanceId:326, grpId:94178, abilityGrpId:183}`

Player used it for mana:

**PayCostsReq (gsId 215):** `{promptId:11}` — empty body. The client responds with `PerformAutoTapActionsResp` (auto-tapped).

**GSM gsId 216:**
```
AbilityInstanceCreated  affectorId:326  affectedIds:[328]  details:{source_zone:28}
TappedUntappedPermanent affectorId:328  affectedIds:[326]  details:{tapped:1}
ObjectIdChanged         affectorId:328  affectedIds:[326]  details:{orig_id:326, new_id:329}
ZoneTransfer            affectorId:328  affectedIds:[329]  details:{zone_src:28, zone_dest:33, category:"Sacrifice"}
UserActionTaken         affectorId:1    affectedIds:[328]  details:{actionType:4, abilityGrpId:183}
ManaPaid                affectorId:326  affectedIds:[327]  details:{id:433, color:2}
AbilityInstanceDeleted  affectorId:326  affectedIds:[328]
UserActionTaken         affectorId:1    affectedIds:[327]  details:{actionType:1, abilityGrpId:0}
TokenDeleted            affectorId:329  affectedIds:[329]
```

- ManaPaid color:2 = Black mana (Treasure produces any color; client/player chose Black here)
- `category:"Sacrifice"` — ZoneTransfer category for sacrifice to GY zone 33
- `TokenDeleted` fires after ZoneTransfer, affectorId = post-ObjectIdChanged iid (329)
- The Treasure goes zone 28 → 33 (GY), not Limbo — tokens that go to GY briefly show in GY before disappearing via `TokenDeleted`

---

## 9. Annotation Gaps / Bugs

### AttachmentCreated / Attachment affectorId confirmed
Wire shape: `affectorId=equipmentIid, affectedIds=[hostCreatureIid]`. This matches the aura shape from 2026-03-21. The bug noted in `exile-aura-wire.md` ("no affectorId, affectedIds=[aura,target]") is incorrect — the wire has affectorId in both aura and equipment cases. Flag for engine-bridge: verify current builder output matches `affectorId=sourceIid, affectedIds=[hostIid]`.

### ModifiedToughness/ModifiedPower pAnn update semantics
On re-equip, pAnn `id:403` keeps its id but updates `affectedIds` to the new host. A new `Attachment` pAnn is issued. Engine must handle pAnn updates (same id, changed affectedIds) vs always deleting+re-adding.

### Triggered ability source
`AbilityInstanceCreated` for the Treasure trigger has `affectorId=320` (the equipment, not the creature). `AbilityInstanceDeleted` also `affectorId=320`. This is correct: the ability belongs to the equipment.

### TriggeringObject affectorId points to ability instance (not source permanent)
`TriggeringObject  affectorId=abilityInstanceIid, affectedIds=[creatureIid]` — same pattern as ward (`affectorId=wardAbilityIid, affectedIds=[wardedCreatureIid]`). The affectorId is the triggered ability instance being annotated, not the equipment that owns the ability.

---

## 10. Summary Table

| Event | Annotation Type | affectorId | affectedIds |
|---|---|---|---|
| Equipment ETB | LayeredEffectCreated | equipment iid | [effect_id 7002] |
| Equip activation → stack | AbilityInstanceCreated | equipment iid | [ability iid] |
| Target selection | SelectTargetsReq (sourceId=abilityIid, abilityGrpId=1268) | — | — |
| Mana paid | ManaPaid | land iid | [ability iid] |
| TargetSpec (persistent) | TargetSpec | ability iid | [target creature iid] |
| Equip resolves | AttachmentCreated | equipment iid | [host creature iid] |
| Equip resolves | Attachment (persistent) | equipment iid | [host creature iid] |
| Equip resolves | ModifiedToughness+ModifiedPower+LayeredEffect (persistent) | equipment iid | [host creature iid] |
| Combat damage trigger | AbilityInstanceCreated | equipment iid | [trigger ability iid] |
| Combat damage trigger | TriggeringObject (persistent) | trigger ability iid | [attacking creature iid] |
| Treasure token created | TokenCreated | trigger ability iid | [token iid] |
| Ability resolved | AbilityInstanceDeleted | equipment iid | [trigger ability iid] |
| Unequip on host death | diffDeletedPersistentAnnotationIds | — | [Attachment id, LayeredEffect id] |
| Treasure sacrifice | ZoneTransfer | mana ability iid | [token new iid] |
| Treasure sacrifice | TokenDeleted | token new iid | [token new iid] |
