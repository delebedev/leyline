# Tamiyo's Compleation — Card Spec

## Identity

- **Name:** Tamiyo's Compleation
- **grpId:** 79508
- **Set:** NEO
- **Type:** Enchantment — Aura
- **Cost:** {3}{U}
- **Forge script:** `forge/forge-gui/res/cardsfolder/t/tamiyos_compleation.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Flash | `K:Flash` | `GameEventSpellAbilityCast` | wired |
| Enchant (artifact, creature, or planeswalker) | `K:Enchant:Artifact,Creature,Planeswalker` | `GameEventCardAttachment` | wired |
| ETB: tap enchanted permanent | `T:Mode$ ChangesZone` → `DB$ Tap` | `GameEventCardTapped` | wired |
| ETB: unattach if Equipment | `DB$ Unattach \| ConditionPresent$ Equipment` | `GameEventCardAttachment` | wired |
| Loses all abilities (continuous) | `S:RemoveAllAbilities$ True` | **none** (static layer, no event) | **missing** (RemoveAbility ann type 23 not wired) |
| Doesn't untap during untap step | `R:Event$ Untap \| Layer$ CantHappen` | **none** (replacement effect) | wired (tap-untap) |

**Unobserved:** Equipment unattach — the target in this session was a creature, not an Equipment. Needs dedicated recording or puzzle.

## What it does

1. **Flash** — can be cast at instant speed.
2. **Enchant** — attaches to target artifact, creature, or planeswalker (chosen on cast, aura targeting rules).
3. **ETB trigger** — when it enters the battlefield, tap the enchanted permanent. If it's an Equipment, unattach it from whatever it's equipped to.
4. **Continuous: loses all abilities** — the enchanted permanent has no abilities for as long as this aura is attached.
5. **Continuous: doesn't untap** — the enchanted permanent skips its controller's untap step (replacement effect preventing the untap event).

Combined effect: the enchanted permanent is tapped, stripped of abilities, and stays tapped. For creatures this is essentially a removal spell — the creature can't attack, block, or use any abilities.

## Trace (session 2026-03-27_21-29-18, seat 1)

Cast once by seat 1 (human, mono-B deck) on turn 7, targeting opponent's Bird Wizard (instanceId 311, grpId 75479). Cast during Main 1 — flash keyword allowed instant-speed but wasn't exercised at unusual timing here.

### Cast to stack (gsId 149)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 149 | 165→322 | Hand→Stack (31→27) | ObjectIdChanged (165→322) + ZoneTransfer CastSpell |
| 149 | 322 | Stack (27) | PlayerSelectingTargets — aura needs target on cast |

### Target selection (gsId 149–150)

SelectTargetsReq sent with sourceId=322. Valid targets included instanceId 311 (Bird Wizard). Player selected 311.

### Resolve to battlefield (gsId 155)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 155 | 322 | Stack→Battlefield (27→28) | ZoneTransfer Resolve + ResolutionStart/Complete |
| 155 | 329 | Stack (27) | ETB trigger ability (grpId 147865, parentId=322) put on stack |
| 155 | 311 | Battlefield (28) | Enchanted creature — still shows P/T 2/2 in gameObject, abilities stripped via pAnn |

### ETB trigger resolution (gsId 157)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 157 | 311 | Battlefield (28) | TappedUntappedPermanent (tapped=1) — creature is now tapped |
| 157 | 329 | deleted | AbilityInstanceDeleted — trigger cleaned up |

### Annotations

**Resolve (gsId 155):**
- `ResolutionStart` (id=391) — affectorId=322, affectedIds=[322], grpid=79508
- `ResolutionComplete` (id=392) — same shape
- `LayeredEffectCreated` (id=394) — affectorId=322, affectedIds=[7005]. The 7005 is the effect_id, not an instanceId.
- `ZoneTransfer` (id=397) — zone_src=27 (Stack), zone_dest=28 (Battlefield), category=`Resolve`
- `AttachmentCreated` (id=399) — affectorId=322, affectedIds=[311]. Standard aura→host shape.
- `AbilityInstanceCreated` (id=400) — affectorId=322, affectedIds=[329]. ETB trigger goes to stack.

**Persistent annotations (gsId 155):**
- `RemoveAbility + LayeredEffect` (pAnn id=393) — **multi-type persistent annotation.** affectorId=322 (the aura), affectedIds=[311] (the creature). Details: key="311" valueString="all" (ability removal scope), effect_id=7005. This is the wire representation of "loses all abilities."
- `Qualification` (pAnn id=395) — affectorId=322, affectedIds=[311]. Details: SourceParent=322, grpid=147866, QualificationType=21, QualificationSubtype=0. **Novel annotation type 42 — not previously wired.** The grpid 147866 is the "loses all abilities and doesn't untap" ability text.
- `Attachment` (pAnn id=398) — affectorId=322, affectedIds=[311]. Standard persistent attachment link.
- `TriggeringObject` (pAnn id=401) — affectorId=329, affectedIds=[322]. Links ETB trigger to source. Deleted after trigger resolves (diffDeletedPersistentAnnotationIds in gs157).

**ETB trigger (gsId 157):**
- `TappedUntappedPermanent` (id=403) — affectorId=329 (trigger ability), affectedIds=[311] (creature), tapped=1

### Key findings

- **RemoveAbility (type 23) is a multi-type pAnn** — it co-occurs with LayeredEffect (type 19) on the same persistent annotation. The key is the target's instanceId as a string, value "all" meaning all abilities removed. This is the first observed instance of RemoveAbility in recordings.
- **Qualification (type 42) observed for first time** — persistent annotation linking the aura's continuous effect to its target. QualificationType=21 is uncharted — no prior documentation of what type 21 means. The grpid points to the ability text, not the card.
- **LayeredEffectCreated affectedIds contains effect_id, not target** — the transient LayeredEffectCreated (id=394) has affectedIds=[7005] which is the effect_id referenced in the persistent RemoveAbility+LayeredEffect pAnn. Different from P/T pump LayeredEffectCreated which puts the target creature in affectedIds.
- **gameObject P/T unchanged** — the enchanted Bird Wizard still reports power=2, toughness=2 in its gameObject at gs155. The ability removal is purely annotation-driven (RemoveAbility pAnn), not reflected in gameObject fields. Client presumably reads the pAnn to suppress ability display.
- **No "0/0 Phyrexian" effect** — the Forge script and Arena ability DB confirm this card does NOT set P/T to 0/0 or add Phyrexian type. Those mechanics are not part of Tamiyo's Compleation.

## Gaps for leyline

1. **RemoveAbility annotation (type 23)** — listed as missing in rosetta.md. Need to emit this as a multi-type persistent annotation `[RemoveAbility, LayeredEffect]` with detail key = target instanceId (string), value = "all", plus effect_id. First real wire data for this annotation type.
2. **Qualification annotation (type 42)** — listed as missing in rosetta.md. Fires as persistent annotation on continuous aura effects. Need to understand QualificationType=21 semantics — possibly "doesn't untap" qualifier. Low priority for visual correctness but may matter for full conformance.
3. **"Doesn't untap" enforcement** — the replacement effect (`R:Event$ Untap | Layer$ CantHappen`) needs verification that leyline correctly prevents untap during the controller's untap step while the aura is attached. The Forge engine handles this, but the wire must suppress the untap.
4. **Update catalog** — add `ability-removal` mechanic entry (status: missing) referencing RemoveAbility type 23 and this card spec.

## Supporting evidence needed

- [ ] Other "loses all abilities" cards in recordings — Planar Disruption (83728) shares abilityGrpId 147864 (enchant) but different continuous effect; Stop Cold (grpId for 147866 shared ability)
- [ ] Qualification pAnn on other aura types — does it fire for all enchant-style continuous effects or only ability-removal?
- [ ] Equipment target scenario — puzzle with Tamiyo's Compleation targeting an Equipment to observe the unattach ETB path
- [ ] "Doesn't untap" wire — trace through an untap step where the enchanted creature should skip untap, confirm no TappedUntapped annotation fires for it
