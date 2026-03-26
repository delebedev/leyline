# Electroduplicate — Card Spec

## Identity

- **Name:** Electroduplicate
- **grpId:** 93798
- **Set:** WOE (Wilds of Eldraine)
- **Type:** Sorcery
- **Cost:** {2}{R}
- **Forge script:** `forge/forge-gui/res/cardsfolder/e/electroduplicate.txt`

## What it does

1. **Cast ({2}{R}):** From hand. Requires targeting one creature you control (SelectTargetsReq, single target group, min=max=1). While on stack, TargetSpec pAnn persists on the spell.
2. **Resolve — copy token:** Creates a token that is a copy of the targeted creature, except the token has haste and "At the beginning of the end step, sacrifice this token." TokenCreated fires (affectorId=spell iid). An EOT-sacrifice triggered ability instance is immediately created on the stack (AbilityInstanceCreated, affectorId=token iid). A TemporaryPermanent pAnn persists on the token (AbilityGrpId=192424). Sorcery moves to graveyard (ZoneTransfer category=Resolve to zone 33).
3. **Flashback ({2}{R}{R}):** Offered in ActionsAvailableReq while the card is in graveyard (`Cast instanceId=412 grpId=93798 abilityGrpId=175847`). Cast fires ZoneTransfer GY(33)→Stack(27) category=CastSpell plus a CastingTimeOption pAnn (type=13, alternateCostGrpId=175847). Targeting and resolution are identical to the hand cast. On flashback resolve, the card exiles instead of going to graveyard (ZoneTransfer category=Resolve to zone 29 Exile).

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Cast sorcery — target creature you control | `A:SP$ CopyPermanent \| ValidTgts$ Creature.YouCtrl` | ObjectIdChanged + ZoneTransfer CastSpell + SelectTargetsReq (targetingAbilityGrpId=175846, abilityGrpId=93798) + PlayerSelectingTargets transient | **missing** |
| TargetSpec pAnn on stack | targeting rule | TargetSpec pAnn (affectorId=spell, affectedIds=[target], abilityGrpId=175846, index=1) persists until resolution | **missing** |
| Resolve — CopyPermanent token with Haste + sac trigger | `CopyPermanent \| AddKeywords$ Haste \| AtEOTTrig$ Sacrifice` | ResolutionStart/Complete + TokenCreated (affectorId=spell, no details) + AbilityInstanceCreated (affectorId=token, grpId=176473) + TemporaryPermanent pAnn (AbilityGrpId=192424) | **missing** |
| Resolve — sorcery to graveyard | `CastSpell → ZoneTransfer Resolve → GY` | ObjectIdChanged + ZoneTransfer zone_src=27 zone_dest=33 category=Resolve affectorId=1 | **missing** |
| Flashback offer in GY | `K:Flashback:2 R R` | ActionsAvailableReq: `Cast instanceId=<iid> grpId=93798 abilityGrpId=175847` while in GY | **missing** |
| Flashback cast from GY | flashback rule | ObjectIdChanged + ZoneTransfer zone_src=33 zone_dest=27 category=CastSpell + CastingTimeOption pAnn (type=13, alternateCostGrpId=175847, castAbilityGrpId=175847) + SelectTargetsReq | **missing** |
| Flashback resolve → exile (not GY) | flashback rule | ZoneTransfer zone_src=27 zone_dest=29 category=Resolve (Exile, not GY) | **missing** |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 93798 | Electroduplicate (Sorcery, {2}{R}) |
| 95237 | Alternate printing (not observed in this session) |
| 175846 | Spell ability targeting (CopyPermanent) |
| 175847 | Flashback alternate-cost ability ({2}{R}{R}) |
| 176473 | EOT-sacrifice triggered ability (on copy token) |
| 192424 | TemporaryPermanent ability (AbilityGrpId on pAnn) |
| 86964 | Spellscorn Coven (Faerie Warlock 2/3) — the copied creature in this session |
| 86965 | Take It Back — adventure side proxy object on the copy token |

## Trace (session 2026-03-25_22-22-14, seat 1)

Seat 1 cast Electroduplicate twice: first from hand targeting iid 364 (Spellscorn Coven, grpId 86964), then via flashback targeting iid 431 (another Spellscorn Coven). Two copy tokens were created.

### Cast 1 from hand (gsId 353–359)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 353 | 165→404 | Hand(31)→Stack(27) | ObjectIdChanged 165→404; ZoneTransfer category=CastSpell; PlayerSelectingTargets pAnn; SelectTargetsReq sourceId=404 abilityGrpId=93798 targetingAbilityGrpId=175846 (6 legal targets offered) |
| 354–355 | 404 | Stack | Player submits target iid 364 (Spellscorn Coven); TargetSpec pAnn id=812 affectorId=404 affectedIds=[364] abilityGrpId=175846 index=1 promptId=152 |
| 359 | 404→412 | Stack→GY(33) | ResolutionStart affectorId=404 grpId=93798; TokenCreated affectorId=404 affectedIds=[409] (copy token grpId=86964); AbilityInstanceCreated affectorId=409 affectedIds=[411] grpId=176473 source_zone=28; ResolutionComplete; ObjectIdChanged 404→412; ZoneTransfer zone_src=27 zone_dest=33 category=Resolve affectorId=1; TemporaryPermanent pAnn id=845 affectorId=409 AbilityGrpId=192424; TriggeringObject pAnn id=841 affectorId=411 affectedIds=[409] |

### Flashback offered (gsId 362)

ActionsAvailableReq at gs362:
```
{ type: "Cast", instanceId: 412, grpId: 93798, abilityGrpId: 175847, shouldStop: true }
```
Flashback is offered as a standard `Cast` action with the flashback abilityGrpId. Same `instanceId` as the card in GY.

### Flashback cast (gsId 417–423)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 417 | 412→437 | GY(33)→Stack(27) | ObjectIdChanged 412→437; ZoneTransfer category=CastSpell zone_src=33; PlayerSelectingTargets pAnn; CastingTimeOption pAnn affectorId=437 type=13 alternateCostGrpId=175847 castAbilityGrpId=175847; SelectTargetsReq sourceId=437 |
| 419 | 437 | Stack | Target chosen (Spellscorn Coven iid 431); TargetSpec pAnn fires |
| 423 | 437→446 | Stack→Exile(29) | ResolutionStart affectorId=437 grpId=93798; TokenCreated affectorId=437 affectedIds=[443] (copy token grpId=86964); AbilityInstanceCreated affectorId=443 affectedIds=[445] grpId=176473; ResolutionComplete; ObjectIdChanged 437→446; ZoneTransfer zone_src=27 zone_dest=29 category=Resolve affectorId=1 |

## Annotations

### Cast shape (gsId 353)

```
ObjectIdChanged       affectedIds=[165], details={orig_id:165, new_id:404}
ZoneTransfer          affectedIds=[404], zone_src=31, zone_dest=27, category=CastSpell
PlayerSelectingTargets  affectorId=1, affectedIds=[404]
EnteredZoneThisTurn   affectorId=27, affectedIds=[404]  (persistent)
```

SelectTargetsReq (msgId 471, gsId 353):
```
sourceId: 404
abilityGrpId: 93798
targets[0].targetIdx: 1
targets[0].minTargets: 1  maxTargets: 1
targets[0].targetingAbilityGrpId: 175846
targets[0].targetingPlayer: 1
```
Six creatures offered as legal targets (all creatures seat 1 controls at that point).

### TargetSpec pAnn (gsId 355)

```
TargetSpec (pAnn id=812)  affectorId=404, affectedIds=[364]
  details: { abilityGrpId:175846, index:1, promptParameters:404, promptId:152 }
```

### Resolve — copy token + sac trigger (gsId 359)

```
ResolutionStart       affectorId=404, affectedIds=[404], grpid=93798
TokenCreated          affectorId=404, affectedIds=[409]  (no details)
AbilityInstanceCreated  affectorId=409, affectedIds=[411], details={source_zone:28}
ResolutionComplete    affectorId=404, affectedIds=[404], grpid=93798
ObjectIdChanged       affectorId=1, affectedIds=[404], details={orig_id:404, new_id:412}
ZoneTransfer          affectorId=1, affectedIds=[412], zone_src=27, zone_dest=33, category=Resolve
```

Persistent annotations surviving past resolution:
```
TriggeringObject (pAnn id=841)   affectorId=411, affectedIds=[409], details={source_zone:28}
TemporaryPermanent (pAnn id=845) affectorId=409, affectedIds=[409], details={AbilityGrpId:192424}
```

The copy token (iid 409, grpId=86964) is type=Token, power=2 toughness=3, subtypes=["Faerie","Warlock"], uniqueAbilityCount=4. It also carries an Adventure proxy object (iid 410, grpId=86965 type=Adventure) — this appears because Spellscorn Coven is an adventure card; the copy token inherits the adventure-proxy shape.

### Flashback — CastingTimeOption pAnn (gsId 417)

```
CastingTimeOption (pAnn id=1028)  affectorId=437, affectedIds=[437], type=13
  details: { alternateCostGrpId:175847, castAbilityGrpId:175847 }
```

type=13 matches the CastingTimeOption shape seen on Disguise/Morph (Nervous Gardener spec) and Adventure cast-from-exile. This pAnn marks the spell as cast via an alternate-cost ability.

### Flashback resolve → exile (gsId 423)

```
ZoneTransfer  affectorId=1, affectedIds=[446], zone_src=27, zone_dest=29, category=Resolve
```

zone_dest=29 (Exile) instead of zone 33 (GY). No Qualification pAnn is added — the card is exiled permanently, not eligible for further casting.

## Gaps for leyline

1. **Flashback cast path (zone_src=33).** Cast from GY must emit CastingTimeOption pAnn (type=13, grpId=abilityGrpId of flashback) and use zone_src=33 in the ZoneTransfer. Current leyline has no special handling for alternate-cost casts from non-hand zones.
2. **Flashback resolve → exile.** After flashback resolves, leyline must exile the card (zone 29) rather than putting it in the graveyard. The flashback rule needs to intercept the post-resolution zone destination.
3. **CopyPermanent token.** The copy token's grpId matches the original creature (86964 here). Token type, power/toughness, subtypes, and uniqueAbilityCount must be derived from the source. If the source is an adventure creature, the copy token also gets an Adventure proxy object (same grpId pattern as a live adventure card on the battlefield).
4. **TemporaryPermanent pAnn.** The copy token needs a `TemporaryPermanent` persistent annotation (affectorId=token, AbilityGrpId=192424) which drives the "sac at EOT" display. Currently untracked in annotation builders.
5. **AbilityInstanceCreated for EOT sac trigger.** The "sacrifice at end of step" trigger on the token fires AbilityInstanceCreated immediately on ETB (affectorId=token, grpId=176473). This is the same pattern as other ETB triggers — leyline must model the token's triggered ability.
6. **Flashback in ActionsAvailableReq.** When the card is in GY and flashback is payable, leyline must offer a `Cast` action with `abilityGrpId=<flashbackAbilityGrpId>` (175847 here), not just the standard cast action with abilityGrpId=0.

## Unobserved mechanics

- **EOT sac trigger resolution.** AbilityInstanceCreated (grpId=176473) fires at token ETB and goes on stack. The trigger's resolution (TokenDeleted + ZoneTransfer Sacrifice) was not decoded in this spec. Pattern is expected to match the standard sac-for-draw shape (Novice Inspector spec).
- **Copy token with different source.** Only Spellscorn Coven (an adventure creature) was copied in this session. Copy targeting a non-adventure creature, a token, or a creature with Equipment attached is unobserved — the Adventure proxy object on the token is a byproduct of the source, not of Electroduplicate itself.
- **Flashback SelectTargetsReq shape.** The flashback SelectTargetsReq was observed (sourceId=437) but the full target list and promptId were not decoded. Expected identical to the hand-cast form.
- **Mana payment for flashback.** The {2}{R}{R} mana bracket was not decoded. Four mana sources expected based on card cost.
