# Sun-Blessed Healer — Card Spec

## Identity

- **Name:** Sun-Blessed Healer
- **grpId:** 93738
- **Set:** FDN
- **Type:** Creature — Human Cleric
- **Cost:** {1}{W}
- **Kicker:** {1}{W}
- **Base P/T:** 3/1
- **Forge script:** `forge/forge-gui/res/cardsfolder/s/sun_blessed_healer.txt`

## What it does

1. **Lifelink** — combat damage this creature deals is also gained as life.
2. **Kicker {1}{W}** — optional additional cost paid at cast time. Server sends CastingTimeOptionsReq (type=Kicker, grpId=9313). The client CastingTimeOptionsResp body is always empty regardless of selection; whether kicker was paid is determined by the CastingTimeOption transient annotation at spell resolution (type=3, kickerAbilityGrpId=9313).
3. **ETB trigger (kicked only)** — when the creature enters, if it was kicked, ability grpId 175778 fires on the stack. The controller selects a target nonland permanent card with mana value 2 or less from their graveyard via SelectTargetsReq. That card returns to the battlefield with ZoneTransfer category="Return".
4. **Fizzle path** — if the ETB trigger fires but no legal target exists in the GY, the ability instance is created then deleted in the same diff (AbilityInstanceCreated + diffDeletedInstanceIds in gs~331).

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Lifelink | `K:Lifelink` | ModifiedLife on combat damage | wired |
| Kicker prompt | `K:Kicker:1 W` | CastingTimeOptionsReq (type=Kicker, ctoId varies, grpId=9313) | wired |
| Kicked ETB: GY→BF recursion | `T:Mode$ ChangesZone … ValidCard$ Card.Self+kicked … DB$ ChangeZone Origin$ Graveyard Destination$ Battlefield ValidTgts$ Permanent.YouOwn+nonLand+cmcLE2` | AbilityInstanceCreated grpId=175778 → SelectTargetsReq (zone 33 targets) → ZoneTransfer category="Return" | **untested in leyline** |
| Fizzle (no GY targets) | same trigger, empty GY | AbilityInstanceCreated → diffDeletedInstanceIds same diff | unobserved in leyline |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 9313 | Kicker cost (CastingTimeOptions) |
| 12 | Lifelink |
| 175778 | ETB trigger (if kicked: return nonland permanent ≤2 MV from GY to BF) |

## Trace (session 2026-03-25_22-37-18, seat 1)

Seat 1 cast Sun-Blessed Healer twice. Both casts paid the kicker. First cast had a legal GY target and the ETB resolved; second cast had no legal targets and the ability fizzled.

### Cast 1 — kicked, ETB resolves (gsId 270→278, iid 317→354)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 270 | 317 | Hand (31) | Cast action selected (PerformActionResp) |
| 271 | 317→354 | Hand→Stack | ObjectIdChanged + ZoneTransfer category=CastSpell; CastingTimeOptionsReq ctoId=4 (Kicker, grpId=9313) + ctoId=0 (Done) |
| 271 | — | — | CastingTimeOptionsResp (C-S, body empty — kicker selection not reflected in body) |
| 272 | — | — | Mana payment (Islands and Plains tapped, iids 281/285/309/353/318) |
| 273 | — | — | Opponent passes priority |
| 274 | 354 | Stack→BF | ResolutionStart/Complete grpId=93738; ZoneTransfer category=Resolve; CastingTimeOption transient annotation (type=3, kickerAbilityGrpId=9313); AbilityInstanceCreated iid=359 grpId=175778 (ETB trigger on stack) |
| 274 | 359 | Stack | SelectTargetsReq: targetIdx=1, targetSourceZoneId=33 (GY), targetingAbilityGrpId=175778, single legal target iid=336 (Drake Hatcher, grpId=93748) |
| 275 | — | — | SelectTargetsReq repeated with selectedTargets=1 (iid 336, legalAction=Unselect) |
| 278 | 336→360 | GY→BF | ResolutionStart/Complete grpId=175778; ObjectIdChanged 336→360; ZoneTransfer zone_src=33 zone_dest=28 category=**Return**; AbilityInstanceDeleted affectorId=354 affectedIds=359 |

### Cast 2 — kicked, ETB fizzles (gsId 327→331, iid 162→366)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 327 | 162 | Hand (31) | Cast action selected |
| 328 | 162→366 | Hand→Stack | ObjectIdChanged + ZoneTransfer category=CastSpell; CastingTimeOptionsReq ctoId=5 (Kicker, grpId=9313) + ctoId=0 (Done); CastingTimeOptionsResp empty |
| 331 | 366 | Stack→BF | ResolutionStart/Complete grpId=93738; ZoneTransfer category=Resolve; CastingTimeOption transient annotation (type=3, kickerAbilityGrpId=9313); AbilityInstanceCreated iid=371; iid=371 in diffDeletedInstanceIds — no legal GY targets, ability fizzled immediately |

Note: ctoId for Kicker was 4 on cast 1 and 5 on cast 2. Server-assigned per cast; Nullpriest observed ctoId=2 and 4. Never stable — must not be hardcoded.

### Key annotations

**Spell resolution with kicker (gsId 274, file 000000287_MD_S-C_MATCH_DATA.bin):**
```json
{"id": 640, "types": ["CastingTimeOption"], "affectorId": 354, "affectedIds": [354],
 "details": {"type": 3, "kickerAbilityGrpId": 9313}}
{"id": 641, "types": ["AbilityInstanceCreated"], "affectorId": 354, "affectedIds": [359],
 "details": {"source_zone": 28}}
```
Persistent:
```json
{"id": 643, "types": ["CastingTimeOption"], "affectorId": 359, "affectedIds": [359],
 "details": {"kickerAbilityGrpId": 0, "type": 3}}
```
Note: transient CastingTimeOption has `kickerAbilityGrpId=9313`; the persistent pAnn on the ability instance has `kickerAbilityGrpId=0`. Discrepancy — both observed.

**GY→BF return (gsId 278, file 000000292_MD_S-C_MATCH_DATA.bin):**
```json
{"id": 648, "types": ["ObjectIdChanged"], "affectorId": 359, "affectedIds": [336],
 "details": {"orig_id": 336, "new_id": 360}}
{"id": 649, "types": ["ZoneTransfer"], "affectorId": 360, "affectedIds": [360],
 "details": {"zone_src": 33, "zone_dest": 28, "category": "Return"}}
```

**SelectTargetsReq (gsId 274, file 000000287_MD_S-C_MATCH_DATA.bin):**
```json
{
  "targets": [{"targetIdx": 1, "targets": [{"targetInstanceId": 336, "legalAction": "Select_a1ad"}],
               "minTargets": 1, "maxTargets": 1,
               "targetSourceZoneId": 33, "targetingAbilityGrpId": 175778, "targetingPlayer": 1}],
  "sourceId": 359, "abilityGrpId": 175778
}
```

## Gaps

1. **CastingTimeOptionsResp opaque** — the C-S response body is always empty in JSONL decode. Kicker selection is inferred from the CastingTimeOption annotation at resolution. Whether the resp body contains a `selectedOptions` field that the decoder drops is unknown. Applies to all kicker cards.

2. **CastingTimeOption pAnn kickerAbilityGrpId=0** — the persistent CastingTimeOption annotation on the ETB ability instance has `kickerAbilityGrpId=0` while the transient on the spell has `kickerAbilityGrpId=9313`. Semantics of the pAnn field value unknown; leyline must not rely on it for kicked detection.

3. **Lifelink annotation shape unobserved** — no combat damage occurred in either cast. Lifelink pAnn (grpId=12) shape not confirmed for this card. Cross-reference prior conformance research (ModifiedLife annotation) once combat is recorded.

4. **ETB return targets non-creature permanents** — Forge DSL target is `Permanent.YouOwn+nonLand+cmcLE2` (enchantments, artifacts, planeswalkers also legal). Only a creature (Drake Hatcher) was observed as the return target. SelectTargetsReq shape for non-creature targets in GY not confirmed; expected identical wire shape.

## Supporting evidence

- `docs/rosetta.md` — zone IDs (Hand=31, Stack=27, BF=28, GY=33)
- `docs/card-specs/nullpriest-of-oblivion.md` — same kicker mechanic; ctoId instability first documented there; kicked ETB was the unobserved gap that this session fills
- prior conformance research (TargetSpec pAnn) — TargetSpec in persistentAnnotations while ability is on stack; not extracted for this session but expected on iid 359 during gs274–278
