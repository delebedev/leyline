# Black Mage's Rod — Card Spec

## Identity

- **Name:** Black Mage's Rod
- **grpId:** 95948
- **Set:** FIN (Final Fantasy)
- **Type:** Artifact — Equipment
- **Cost:** {1}{U}
- **Forge script:** `forge/forge-gui/res/cardsfolder/b/black_mages_rod.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Cast artifact | `A:SP$ …` | ZoneTransfer CastSpell | wired |
| Job select (ETB: create 1/1 Hero token, auto-attach to it) | `T:Mode$ ChangesZone … Execute$ TrigToken\|TrigEquip` | TokenCreated + AttachmentCreated | **missing — job_select not wired** |
| Equipped creature gets +1/+0, Wizard, noncreature-spell trigger | `K:SetInMotion$ … \| IsSVar$ EquipAbility` | LayeredEffectCreated ×3 | **unknown — layered effect mapping unverified** |
| Equip {3} | `A:AB$ Attach \| Cost$ 3` | AttachmentCreated | wired (standard equip) |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 364 | Job select (ETB triggered — creates Hero token + auto-attaches) |
| 189183 | Static: equipped creature +1/+0, Wizard, noncreature trigger |
| 1156 | Equip {3} (activated) |

## What it does

1. **Cast** ({1}{U}): artifact enters the stack, pays one blue + one generic mana.
2. **Resolution**: enters battlefield. Three layered effects register immediately (effect_ids 7003/7004/7005). The job_select triggered ability (grpId 364) is queued from the battlefield in the same GSM.
3. **Job select trigger (grpId 364)**: resolves in the next GSM with no player interaction. Creates a 1/1 white Hero creature token (grpId 96212). The equipment attaches to that token automatically — no equip cost paid, no SelectTargetsReq. Both `TokenCreated` and `AttachmentCreated` fire in the same resolution GSM.
4. **Static ability while equipped**: the equipped creature gets +1/+0, gains the subtype Wizard, and gains a triggered ability ("whenever you cast a noncreature spell, this creature deals 1 damage to each opponent").
5. **Equip {3}**: standard equip activation — puts ability on stack, uses SelectTargetsReq (promptId 1010), pays {3} inline (no PayCostsReq).

## Trace (session 2026-03-25_22-22-14, seat 1, T15 Main1)

Seat 1 cast Black Mage's Rod (hand iid 398→399) on turn 15. The job_select trigger resolved in the immediately following GSM.

### Cast

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 348 | 398→399 | Hand→Stack (31→27) | Cast: ObjectIdChanged 398→399, ZoneTransfer Hand→Stack, category=CastSpell; Island tapped (color=3) + Forest tapped (color=4) |
| 349 | 399 | Stack (27) | Priority window |

### Resolution + job_select trigger queued (gs=350)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 350 | 399 | Stack→BF (27→28) | ResolutionStart grpid=95948; LayeredEffectCreated ×3 (effect_ids 7003, 7004, 7005) affector=399; ZoneTransfer Stack→BF category=Resolve; AbilityInstanceCreated affector=399 affected=[402] source_zone=28 (job_select trigger queued) |

### Job select trigger resolution (gs=352)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 352 | 402 | Stack | ResolutionStart grpid=364; TokenCreated affector=402 affected=[403] (no details); AttachmentCreated affector=399 affected=[403]; ResolutionComplete grpid=364; AbilityInstanceDeleted affector=399 affected=[402] |

Hero token (iid 403): grpId=96212, 1/1, type=Creature, subtype=Hero, white. No SelectTargetsReq or mana cost for the attachment — fully automatic.

### Post-resolution state

After gs=352 the rod (iid 399) is attached to the Hero token (iid 403). The Attachment persistent annotation has affector=399, affectedIds=[403]. Three LayeredEffect pAnns (from gs=350) remain active representing the static bonus.

## Annotations

### Job select resolution (gs=352)

All transient annotations share affectorId=402 (the triggered ability instance), except AttachmentCreated which uses affectorId=399 (the equipment):

- `ResolutionStart` — affectorId=402, affectedIds=[402], details={grpid: 364}
- `TokenCreated` — affectorId=402, affectedIds=[403], details={} (no details, consistent with token-creation-wire.md)
- `AttachmentCreated` — affectorId=399, affectedIds=[403]
- `ResolutionComplete` — affectorId=402, affectedIds=[402], details={grpid: 364}
- `AbilityInstanceDeleted` — affectorId=399, affectedIds=[402]

**Key distinction from standard equip:** the `AttachmentCreated` affectorId here is the equipment (399), not the triggered ability instance (402). This matches the existing equipment-wire.md pattern. No `Attachment` persistent annotation was observed in this GSM — the persistent pAnn may appear in the same diff object rather than as a separate annotation entry; flag for verification.

### Resolution enter (gs=350)

- `ResolutionStart` — affectorId=399, affectedIds=[399], details={grpid: 95948}
- `LayeredEffectCreated` ×3 — affectorId=399, affectedIds=[7003], [7004], [7005]
- `ZoneTransfer` — affectorId=1 (seat), affectedIds=[399], zone_src=27, zone_dest=28, category=Resolve
- `AbilityInstanceCreated` — affectorId=399, affectedIds=[402], source_zone=28
- `ResolutionComplete` — affectorId=399, affectedIds=[399], details={grpid: 95948}

Three LayeredEffectCreated annotations fire at ETB, before the job_select trigger GSM. The three effect slots presumably map to: +1/+0, add Wizard type, and the noncreature-spell triggered ability. Exact slot assignment unverified.

### Cast mana sequence (gs=348)

Standard two-mana cast pattern: AbilityInstanceCreated (mana source) → TappedUntappedPermanent → UserActionTaken actionType=4 → ManaPaid → AbilityInstanceDeleted, repeated per land. color=3 (blue) from Island, color=4 (green) from Forest. UserActionTaken actionType=1 abilityGrpId=0 closes the cast.

## Gaps for leyline

1. **job_select not wired.** The ETB trigger (grpId 364) must create a 1/1 Hero token (grpId 96212) and immediately attach the equipment to it — without any player interaction, without paying equip cost, and without a SelectTargetsReq. `TokenCreated` and `AttachmentCreated` must both fire in the same resolution GSM as `ResolutionComplete` for grpId=364.
2. **AttachmentCreated affectorId in job_select context.** Standard equip uses `affectorId=equipmentIid`. Verify leyline emits the same shape here (not `affectorId=triggerAbilityIid`).
3. **Persistent Attachment pAnn after auto-attach.** Confirm that the Attachment persistent annotation (affector=equipmentIid, affectedIds=[heroTokenIid]) appears in the diff object at gs=352 in addition to the transient AttachmentCreated.
4. **Three LayeredEffectCreated at ETB.** The three effect slots (7003/7004/7005) for +1/+0, Wizard subtype, and the noncreature-spell trigger must all register before the job_select trigger fires. Verify leyline's ETB handler emits these in the same GSM as Resolve, not deferred.
5. **Hero token grpId 96212.** Verify `TokenRegistry` maps the FIN Hero token to grpId 96212. The token has no abilities in the recording (no Equip ability on the token itself).
6. **Standard Equip {3} (abilityGrpId 1156) still needs testing.** The auto-attach at ETB was observed; a manual re-equip to a different creature was not exercised in this session.

## Supporting evidence

- `equipment-wire.md` — establishes baseline equip wire shape (SelectTargetsReq, inline mana, AttachmentCreated affectorId pattern). The job_select auto-attach differs in that no SelectTargetsReq fires and no mana is paid.
- `token-creation-wire.md` — TokenCreated transient has no details field; affectorId is the resolving ability instance. Consistent here.
- `docs/catalog.yaml` equipment entry — standard equip is marked wired; job_select is not listed.

**Single data point caveat:** this is the only job_select card captured in our recordings. No variance across multiple casts was possible. The auto-attach sequence (TokenCreated + AttachmentCreated in a single triggered ability resolution, no player input) is confirmed by two identical GSM snapshots in the same session (idx=430 and idx=708 both show gs=352), but the mechanic has not been observed across different job_select cards. Other FIN job_select cards (e.g. Dragoon's Lance grpId 95871, Astrologian's Planisphere grpId 95902) may exhibit the same pattern but are unconfirmed.
