# Ratcatcher Trainee — Card Spec

## Identity

- **Name:** Ratcatcher Trainee / Pest Problem
- **grpId (creature):** 86845
- **grpId (adventure):** 86846
- **Set:** WOE (Wilds of Eldraine)
- **Type:** Creature — Human Peasant (creature side) / Instant — Adventure (adventure side)
- **Cost:** {1}{R} (creature) / {2}{R} (adventure)
- **P/T:** 2/1
- **Forge script:** `forge/forge-gui/res/cardsfolder/r/ratcatcher_trainee_pest_problem.txt`

## What it does

1. **CastAdventure (Pest Problem, {2}{R}):** From hand, cast the adventure side. The card gets a new iid (86846 grpId on stack). A companion Adventure proxy object (same grpId, type=Adventure) is created alongside it. No SelectTargetsReq — Pest Problem targets nothing. On resolution: two 1/1 black Rat tokens enter the battlefield (grpId 87031). The card then moves to Exile with a new iid. A Qualification pAnn (type 47, grpId 196) marks the exiled card as eligible to be cast as a creature from exile.
2. **Cast from exile ({1}{R}):** The creature side is cast from exile zone (zone_src=29). New iid assigned. ZoneTransfer category=CastSpell, same as a normal cast. The Qualification pAnn and EnteredZoneThisTurn(Exile) pAnn are deleted at cast time.
3. **Creature resolves:** Standard creature ETB — ZoneTransfer Resolve into battlefield. A conditional static ability (first strike during your turn) registers immediately as a LayeredEffect (grpId 6, AddAbility pAnn, UniqueAbilityId 220).
4. **Static: first strike during your turn.** As long as it is your turn, Ratcatcher Trainee has first strike. Represented as AddAbility+LayeredEffect pAnn (grpId 6) present on the permanent while on battlefield.

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| CastAdventure (adventure side from hand) | `AlternateMode:Adventure` | ObjectIdChanged + ZoneTransfer CastSpell; objects: Card(grpId=86846) + Adventure proxy (type=Adventure) | **missing — adventure not wired** |
| Adventure resolves: create two Rat tokens | `A:SP$ Token \| TokenAmount$ 2 \| TokenScript$ b_1_1_rat_noblock` | ResolutionStart/Complete (grpId=86846) + TokenCreated ×2 affector=spellIid | **missing** |
| Post-resolve exile to cast-from-exile zone | adventure rule | ObjectIdChanged + ZoneTransfer(Resolve) to Exile (zone 29) + Qualification pAnn type47/grpId=196 + Adventure proxy deleted | **missing** |
| Cast creature from exile | adventure rule | ObjectIdChanged + ZoneTransfer CastSpell zone_src=29; Qualification pAnn + EnteredZoneThisTurn(Exile) deleted at cast | **missing** |
| Creature ETB: conditional first strike (your turn) | `S:Mode$ Continuous \| AddKeyword$ First Strike \| Condition$ PlayerTurn` | AddAbility+LayeredEffect pAnn (grpId 6, effect_id 7002) + LayeredEffectCreated at Resolve | **missing — conditional static not wired** |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 86845 | Creature side (Ratcatcher Trainee, 2/1 {1}{R}) |
| 86846 | Adventure side (Pest Problem, instant {2}{R}) |
| 87031 | Rat token (1/1 black, can't block) |
| 196 | Qualification grpId for cast-from-exile eligibility |
| 6 | AddAbility: First Strike (conditional static) |

## Trace (session 2026-03-25_22-22-14, seat 1, T9 Main1)

Seat 1 held Ratcatcher Trainee (iid 338, grpId 86845) in hand. Adventure side was cast, then the creature was cast from exile in the same turn.

### Adventure cast: hand → stack (gsId 194)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 192–193 | 338 | Hand | ActionsAvailableReq offers both `Cast instanceId=338 grpId=86845` and `CastAdventure instanceId=338 grpId=86846` |
| 194 | 338→341 | Hand→Stack (31→27) | ObjectIdChanged 338→341; ZoneTransfer affectedIds=[341] zone_src=31 zone_dest=27 category=CastSpell; two Swamps + one Mountain tapped ({2}{R}); UserActionTaken actionType=16 abilityGrpId=0 (CastAdventure submit); iid 342 created as Adventure proxy (grpId=86846, type=Adventure, same zone=27); creature iid 338 (grpId=86845) appears in Limbo (zone=30) |

### Adventure resolves: tokens + exile (gsId 196)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 196 | 341 | Stack→Exile | ResolutionStart affectorId=341 grpid=86846; TokenCreated affectorId=341 affectedIds=[346] (Rat iid 346 grpId=87031); TokenCreated affectorId=341 affectedIds=[347] (Rat iid 347 grpId=87031); ResolutionComplete affectorId=341 grpid=86846; ObjectIdChanged affectedIds=[341] orig_id=341 new_id=348; ZoneTransfer affectedIds=[348] zone_src=27 zone_dest=29 category=Resolve; Adventure proxy iid 342 deleted (diffDeletedInstanceIds=[342]) |

Post-resolve: iid 348 (grpId=86845) in Exile (zone 29). Persistent annotations:
- `EnteredZoneThisTurn` affectorId=29 affectedIds=[348]
- `Qualification` affectedIds=[348] grpId=196 QualificationType=47 QualificationSubtype=0 SourceParent=0

### Cast from exile: exile → stack (gsId 197)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 197 | 348→350 | Exile→Stack (29→27) | ObjectIdChanged orig_id=348 new_id=350; ZoneTransfer affectedIds=[350] zone_src=29 zone_dest=27 category=CastSpell; one Swamp + one Mountain tapped ({1}{R}); UserActionTaken actionType=1 abilityGrpId=0; iid 351 created as Adventure proxy (grpId=86846, type=Adventure); Qualification pAnn 446 + EnteredZoneThisTurn(Exile) pAnn 6 deleted (diffDeletedPersistentAnnotationIds=[446, 6]) |

### Creature resolves: stack → battlefield (gsId 199)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 199 | 350 | Stack→BF (27→28) | ResolutionStart affectorId=350 grpid=86845; LayeredEffectCreated affectorId=350 affectedIds=[7002]; ZoneTransfer affectorId=1 affectedIds=[350] zone_src=27 zone_dest=28 category=Resolve; ResolutionComplete affectorId=350 grpid=86845; AddAbility+LayeredEffect pAnn fires (grpId=6, effect_id=7002, UniqueAbilityId=220) |

## Annotations

### Adventure cast shape (gsId 194)

```
ObjectIdChanged       affectedIds=[338], details={orig_id:338, new_id:341}
ZoneTransfer          affectedIds=[341], zone_src=31, zone_dest=27, category=CastSpell
ManaPaid ×3           per-land (Swamp color=3, Swamp color=3, Mountain color=4), affectedIds=[341]
UserActionTaken       affectorId=1, affectedIds=[341], actionType=16, abilityGrpId=0
EnteredZoneThisTurn   affectorId=27, affectedIds=[341]  (persistent)
```

Two game objects exist simultaneously on the stack:
- iid 341 grpId=86846 type=`Card` subtype=Adventure (the castable spell)
- iid 342 grpId=86846 type=`Adventure` (companion proxy — deleted at resolution, gsId 196)

The creature body (iid 338, grpId=86845) moves to Limbo in the same diff.

### Adventure resolve + exile (gsId 196)

```
ResolutionStart       affectorId=341, affectedIds=[341], grpid=86846
TokenCreated          affectorId=341, affectedIds=[346]  (no details field)
TokenCreated          affectorId=341, affectedIds=[347]  (no details field)
ResolutionComplete    affectorId=341, affectedIds=[341], grpid=86846
ObjectIdChanged       affectedIds=[341], orig_id=341, new_id=348
ZoneTransfer          affectedIds=[348], zone_src=27, zone_dest=29, category=Resolve
Qualification (pAnn)  affectedIds=[348], grpId=196, QualificationType=47
```

`affectorId` on ZoneTransfer and ObjectIdChanged here is `1` (seatId), not the spell's own iid. This matches the Claim-the-Firstborn pattern where the "mover" is the seat rather than an ability instance.

### Cast-from-exile shape (gsId 197)

```
ObjectIdChanged       affectedIds=[348], orig_id=348, new_id=350
ZoneTransfer          affectedIds=[350], zone_src=29, zone_dest=27, category=CastSpell
ManaPaid ×2           per-land ({1}{R})
UserActionTaken       affectorId=1, affectedIds=[350], actionType=1, abilityGrpId=0
```

Category is `CastSpell`, identical to a normal hand cast. The source zone (29 = Exile) distinguishes it from a hand cast. The `Qualification` pAnn on the exiled iid is deleted at this point.

### First-strike conditional static (gsId 199)

```
LayeredEffectCreated  affectorId=350, affectedIds=[7002]
AddAbility+LayeredEffect (pAnn)  affectorId=350, affectedIds=[350]
  details: { originalAbilityObjectZcid:350, UniqueAbilityId:220, grpid:6, effect_id:7002 }
```

grpId 6 is First Strike. The AddAbility+LayeredEffect combo matches the Claim-the-Firstborn Haste pattern in prior conformance research (ControllerChanged wire). This is a self-referential conditional static — the permanent grants itself an ability based on a condition.

## Gaps for leyline

1. **Adventure mode not wired.** CastAdventure is a separate action type in ActionsAvailableReq (type=`CastAdventure`, grpId=adventure-side grpId). On user selection, leyline must: assign new iid (grpId=adventure), create Adventure proxy object in same zone, move creature body to Limbo, emit ZoneTransfer CastSpell with adventure iid.
2. **Adventure proxy object (type=Adventure).** Two game objects appear on the stack when an adventure is cast: one type=`Card` (castable), one type=`Adventure` (companion proxy). The proxy is deleted (diffDeletedInstanceIds) at resolution. Purpose: client renders the adventure-card duality. Leyline must emit both.
3. **Post-resolve exile with Qualification pAnn.** After adventure resolves, ZoneTransfer category=Resolve moves the card to Exile (not Graveyard). A `Qualification` pAnn (QualificationType=47, grpId=196) must persist on the exiled iid to enable cast-from-exile option. pAnn must be deleted when the creature is cast from exile.
4. **Cast-from-exile zone_src=29.** The ZoneTransfer for casting from exile uses zone_src=29 (Exile), category=CastSpell. Leyline's cast path must account for the non-hand source zone; the annotation shape is otherwise identical to a normal cast.
5. **Conditional self-granting static (first strike).** The S: static (`Condition$ PlayerTurn`) must register as AddAbility+LayeredEffect pAnn (grpId=6) at ETB. Current leyline does not map conditional self-statics to AddAbility pAnns.
6. **Rat token grpId 87031.** `TokenRegistry` (or equivalent) must map Pest Problem's token output to grpId 87031. Two tokens are created; each fires a separate TokenCreated annotation.

## Unobserved mechanics

- **CastingTimeOptionsReq for adventure.** No CastingTimeOptionsReq was present in this session. The player chose adventure via `CastAdventure` action in the ActionsAvailableReq — the client does not require a separate modal. It is unknown whether a CastingTimeOptionsReq fires in other clients or game states.
- **Adventure side targeting.** Pest Problem creates tokens with no targeting. Adventure spells that target (e.g., targeting a creature) would require a SelectTargetsReq between cast and resolution. Shape unconfirmed.
- **Creature ETB triggered abilities on adventure creatures.** Ratcatcher Trainee has no ETB trigger. The ETB trigger wire for other adventure creatures (AbilityInstanceCreated pattern) is not observed here.
- **Return to hand / bounce interactions.** Adventure card returned to hand after exile (e.g., via Unsummon) — whether Qualification pAnn persists through zone changes is not observed.

## Supporting evidence

- prior conformance research (Exile category, DisplayCardUnderCard) — Exile ZoneTransfer category and Qualification pAnn context (type 47 = CardRevealed per rosetta.md row 42/47)
- prior conformance research (TokenCreated: no details, affectorId=ability instance) — TokenCreated transient shape: no details field, affectorId=resolving ability iid
- prior conformance research (ControllerChanged wire) — AddAbility+LayeredEffect pAnn shape for grpId=6 (Haste); same shape observed here for First Strike (grpId=6 confirmed)
- `docs/rosetta.md` — Qualification annotation type 42, QualificationType 47 = CardRevealed
