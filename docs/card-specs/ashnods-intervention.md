# Ashnod's Intervention — Card Spec

## Identity

- **Name:** Ashnod's Intervention
- **grpId:** 82569
- **Set:** BRO
- **Type:** Instant
- **Cost:** {B}
- **Forge script:** `forge/forge-gui/res/cardsfolder/a/ashnods_intervention.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| P/T pump (+2/+0) | `SP$ Pump \| NumAtt$ +2` | `GameEventCardStatsChanged` | wired (layered-effect) |
| Grant triggered ability | `DB$ Animate \| Triggers$ TrigDieExile` | none (Animate is DSL-internal) | **missing** (AddAbility pAnn type 9) |
| Delayed trigger (dies/exile → hand) | `Mode$ ChangesZone \| Origin$ Battlefield \| Destination$ Graveyard,Exile` | `GameEventCardChangeZone` | **partial** (zone transfers wired; delayed trigger orchestration unknown) |
| Zone transfer: GY → Hand | `DB$ ChangeZone \| Destination$ Hand` | `GameEventCardChangeZone` | wired |

## What it does

1. **Pump:** target creature gets +2/+0 until end of turn.
2. **Grant ability:** that creature gains "When this creature dies or is put into exile from the battlefield, return it to its owner's hand."
3. **Delayed trigger:** if the creature dies or is exiled this turn, the granted trigger fires — a new Ability object (grpId 153272) goes on the stack, resolves, and returns the creature from its current zone (GY or Exile) to owner's hand.

## Trace (session 2026-03-27_23-55-23, seat 1)

Cast once during declare blockers (turn 8). Target: Cauldron Familiar (instanceId 318, grpId 70228). The creature died to combat damage in the same combat — delayed trigger fired and returned it to hand.

### Cast and resolve

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 188 | 316→336 | Hand→Stack (31→27) | Cast: ObjectIdChanged + ZoneTransfer(CastSpell), target selection via SelectTargetsReq |
| 190 | 336 | Stack (27) | Target locked (Cauldron Familiar 318) |
| 192 | 336→338 | Limbo→GY (30→33) | Resolve: +2/+0 applied (LayeredEffectCreated + PowerToughnessModCreated), ability granted (AddAbility pAnn), ObjectIdChanged 336→338, spell→GY (Resolve category) |

### Delayed trigger fires

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 194 | 318→339 | Battlefield→GY (28→33) | Cauldron Familiar dies to SBA_Damage; ObjectIdChanged 318→339; TriggeringObject pAnn (id 440) records source_zone=28, NEW_OBJECT_ID=339 |
| 194 | 341 | Stack (27) | Delayed trigger ability (grpId 153272) created on stack; parentId=318, objectSourceGrpId=70228 (Cauldron Familiar) |
| 196 | 339→342 | GY→Hand (33→31) | Trigger resolves: ResolutionStart(grpid=153272), ObjectIdChanged 339→342, ZoneTransfer zone_src=33 zone_dest=31 category=`Put`, ResolutionComplete, AbilityInstanceDeleted |

### Annotations

**Spell resolution (gsId 192):**
- `LayeredEffectCreated` (id 429) — affectorId=336, affectedIds=[7003]. Transient; buff effect tracking.
- `PowerToughnessModCreated` (id 430) — affectorId=336, affectedIds=[318], power=+2, toughness=0.
- `LayeredEffectCreated` (id 432) — affectorId=336, affectedIds=[7004]. Second layered effect for the granted ability.
- Persistent `AddAbility` + `LayeredEffect` (id 431) — affectorId=336, affectedIds=[318], grpid=153272, UniqueAbilityId=182. This is the granted "dies/exile → return" trigger.
- Persistent `ModifiedPower` + `LayeredEffect` (id 428) — affectedIds=[318], effect_id=7003. The +2/+0 buff.

**Delayed trigger resolution (gsId 196):**
- `ResolutionStart` (id 447) — affectorId=341, grpid=153272. The granted ability resolving.
- `ZoneTransfer` (id 451) — affectorId=341, affectedIds=[342], zone_src=33 (GY), zone_dest=31 (Hand), **category=`Put`**. Not `Resolve` — this is a triggered ability putting a card into a zone.
- `RevealedCardCreated` (id 449) — affectorId=343. Briefly reveals the returned card (public info).
- `AbilityInstanceDeleted` (id 453) — affectorId=318, affectedIds=[341]. Cleanup of the trigger ability.

### Key findings

- **Two LayeredEffectCreated annotations** from one spell — one for the +2/+0 buff (effect_id 7003), one for the granted triggered ability (effect_id 7004). Both are transient; the persistent forms are separate (ModifiedPower+LayeredEffect for buff, AddAbility+LayeredEffect for ability grant).
- **AddAbility persistent annotation (type 9)** confirms the granted ability: grpid=153272 (the "dies/exile" trigger text), UniqueAbilityId=182. This is the first fully traced AddAbility in a card spec — catalog lists it as missing.
- **Delayed trigger uses `Put` category** for the return-to-hand ZoneTransfer, not `Resolve`. Makes sense — it's a triggered ability putting a card, not a spell resolving.
- **TriggeringObject persistent annotation** (id 440) on the death event: affectorId=341 (trigger ability), affectedIds=[318] (original creature ID), with NEW_OBJECT_ID=339 and source_zone=28. This is how the trigger tracks what died and where.
- **Persistent annotations cleaned up on resolution:** diffDeletedPersistentAnnotationIds includes 428 (the +2/+0 buff) and 431 (the AddAbility grant) at gsId 194, after the creature died. End-of-turn cleanup would normally handle these, but death preempts it.
- **ID chain:** spell 316→336→338(GY); creature 318→339(died)→342(returned to hand). Three ObjectIdChanged hops for the creature across the delayed trigger sequence.

## Gaps for leyline

1. **AddAbility annotation (type 9)** — catalog lists as missing. Need to emit AddAbility persistent annotation when a spell grants a triggered ability. Wire shape: affectorId=spell iid, affectedIds=[target creature iid], details: grpid (ability text), UniqueAbilityId, originalAbilityObjectZcid.
2. **Delayed triggered ability orchestration** — when the granted creature dies/is exiled, leyline must create an Ability object (grpId=153272) on the stack with parentId=original creature iid and objectSourceGrpId=source card grpId. Forge fires `GameEventCardChangeZone`; bridge must detect the AddAbility pAnn on the dying creature and spawn the trigger.
3. **TriggeringObject persistent annotation** — on death/exile, emit TriggeringObject pAnn with affectorId=trigger ability iid, affectedIds=[original creature iid], details: NEW_OBJECT_ID (post-ObjectIdChanged iid), source_zone (28=Battlefield).
4. **`Put` category for triggered return** — ZoneTransfer from GY→Hand uses category `Put`, not `Resolve`. Verify leyline's ZoneTransfer builder uses correct category for triggered ability zone changes.
5. **RevealedCardCreated** on return-to-hand — briefly reveals the card being returned. Low priority but contributes to visual conformance.
6. **Dual LayeredEffectCreated** — one spell producing two distinct effect_ids (buff + ability grant). Verify EffectTracker handles multiple effects from a single source.

## Supporting evidence needed

- [ ] Other cards granting triggered abilities (Unlikely Aid, Supernatural Stamina) — cross-check AddAbility wire shape
- [ ] Exile path: game where the buffed creature is exiled (not dies) to confirm the trigger fires with zone_src=29
- [ ] Puzzle: Ashnod's Intervention on a creature that blocks and dies — verify delayed trigger + return-to-hand sequence
