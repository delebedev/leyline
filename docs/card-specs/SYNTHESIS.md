# Card Spec Synthesis — Horizontal Layers

Cross-cut analysis of gaps from all 25 card specs. Each layer is a focused work item
that unblocks multiple cards. Ordered by impact (cards unblocked).

## Tier 1 — Data registration (no logic changes)

### Counter type mapper
Add missing counter type numbers to the mapper.

| Type | Number | Card specs |
|------|--------|-----------|
| INCUBATION | 200 | drake-hatcher |
| LANDMARK | 127 | treasure-map |
| BORE | 182 | brasss-tunnel-grinder |
| LORE | 108 | tribute-to-horobi |
| LOYALTY | 7 | kaito-shizuki (confirmed ETB + activation) |

### Token grpId registry
Register token grpIds so TokenCreated emits the correct ID.

| Token | grpId | Card specs |
|-------|-------|-----------|
| Clue | 89236 | novice-inspector |
| Hero (1/1 white) | 96212 | black-mages-rod |
| Drake (2/2 flying) | 94163 | drake-hatcher |
| Treasure | 87485 | treasure-map |
| Rat (1/1) | 87031 | ratcatcher-trainee |
| Rat Rogue (1/1 black) | 79747 | tribute-to-horobi |
| Keimi (3/3 BG legendary Frog) | 79755 | tatsunari-toad-rider |
| Demon (5/5 flying) | ? | archfiends-vessel (unobserved) |

### Zone 12 = phaseout zone
Kaito spec confirms zone 12 is the phaseout zone. Not in the standard zones array — permanents
move here when phased out, return to Battlefield on phase-in. Add to ZoneIds constants and
zone tracker.

**Card specs:** kaito-shizuki

### Zone 37 = P2 Graveyard
Surgehacker spec sends dead creature to zone 37. This is seat 2's graveyard (per-seat zone).
Already in rosetta Table 3 but the spec flagged it as unknown — confirmed.

## Tier 2 — Shared protocol handlers

### Transform (silent grpId mutation)
One implementation covers all DFCs and sagas. Two variants observed:

**In-place mutation (DFC):** grpId swap in Diff object, no ZoneTransfer, no dedicated annotation.
- **Card specs:** treasure-map, brasss-tunnel-grinder
- **Existing issue:** #191 (DFC / Saga flip wire)
- **Confirmed pattern:** Treasure Map 87432→87433, Concealing Curtains (prior work)

**Exile-return transform (Saga chapter III):** Two-step ZoneTransfer with two ObjectIdChanged.
BF→Exile (category=`Exile`) then Exile→BF (category=`Return`) with grpId change.
- **Card specs:** tribute-to-horobi (79552→79553, full lifecycle traced)
- **Existing issue:** #191
- **Key finding:** Both old IDs persist in Limbo (zone 30) for animation continuity

### Cast-from-non-hand
ActionMapper must offer cast actions for cards in zones other than Hand.

| Origin zone | Action type | Category | Card specs |
|-------------|------------|----------|-----------|
| GY (33) | Cast (abilityGrpId distinguishes flashback) | CastSpell | think-twice, electroduplicate |
| GY (33) | CastAdventure (actionType=16) | CastSpell | ratcatcher-trainee |
| Exile (29) | CastSpell | CastSpell | ratcatcher-trainee |

**Flashback detail (from electroduplicate):** same `Cast` action type, distinguished by `abilityGrpId` on the action. `CastingTimeOption` pAnn `type=13` marks alternate cost (same type as Disguise/Morph). Resolves to Exile (category=Resolve, zone 29).

Wire shape is identical to hand cast — only zone_src differs. The server uses same CastSpell category for all origins.
**Existing issue:** #173 (Cast adventure: 5 code gaps)

### AbilityWordActive annotation
Persistent annotation tracking ability word conditions.

| Word | Timing | Card specs |
|------|--------|-----------|
| Threshold (value=GY count, threshold=7) | every diff | kiora-the-rising-tide |
| Descended (no value/threshold fields) | presence flag | brasss-tunnel-grinder |
| Case solve (value=condition count, threshold=N) | **fires at cast (on stack)** | case-of-the-pilfered-proof |

**Correction from Case spec:** AbilityWordActive fires at **cast time** (on stack), not at resolve. Value resets at NewTurnStarted. Solving is silent — no discrete annotation, just value meeting threshold.

**Existing issue:** #177 (AbilityWordActive annotation not emitted)

### Qualification annotation (type 42) — NEW
Persistent annotation for continuous effects and ability restrictions. Three distinct usages confirmed:

| QualificationType | Context | Card specs |
|-------------------|---------|-----------|
| 47 | Adventure exile marker (enables cast-from-exile) | ratcatcher-trainee |
| 21 | "Loses all abilities + doesn't untap" (aura continuous effect) | tamiyos-compleation |
| CantBeBlockedByObjects | Evasion grant ("can't be blocked except by...") | tatsunari-toad-rider |

**Key findings:**
- Type 42 in rosetta — currently MISSING. First wire data from 3 separate cards.
- CantBeBlockedByObjects value (282) — meaning unclear, may encode the exception condition
- SourceParent detail key links back to source permanent
- grpid detail key points to the ability text, not the card

**Card specs:** tamiyos-compleation, tatsunari-toad-rider, ratcatcher-trainee

### RemoveAbility annotation (type 23) — NEW
Multi-type persistent annotation: `[RemoveAbility, LayeredEffect]`. First wire data.

- affectorId = aura iid, affectedIds = target creature
- Detail: key = target instanceId (string), value = "all"
- effect_id links to the LayeredEffectCreated transient
- gameObject P/T unchanged — ability removal is purely annotation-driven

**Card specs:** tamiyos-compleation
**Existing rosetta entry:** type 23, listed as MISSING. Now has concrete wire shape.

### GY→BF return (category = "Return")
Transfer category **confirmed: "Return"** via Sun-Blessed Healer kicked ETB (gsId 278) and
Tribute to Horobi exile-return transform (Exile→BF category=`Return`).

SelectTargetsReq shape: targetSourceZoneId=33 (GY), targetingAbilityGrpId=ability grpId, targetIdx=1, min/maxTargets=1. Fizzle: no legal targets → ability deleted in same diff, no SelectTargetsReq sent.

**Card specs needing this:** nullpriest-of-oblivion, cauldron-familiar, cleric-class, archfiends-vessel, sun-blessed-healer, tribute-to-horobi

### PayCostsReq variants
Four distinct PayCostsReq shapes confirmed:

| promptId | Cost type | Card specs |
|----------|-----------|-----------|
| 1024 | Mandatory additional cost (discard) | mardu-outrider |
| 1074 | Sacrifice-as-cost (activated ability) | immersturm-predator |
| 1010 | Equip targeting (SelectTargetsReq) | black-mages-rod (standard equip) |
| Select (effectCostType) | Crew (power-weighted creature selection) | surgehacker-mech |

**Crew cost detail:** `effectCostType: Select`, `weights` = creature power, `minSel` = crew number, `context: NonManaPayment`, `optionContext: Payment`.

**Existing issue:** #192 (Mandatory additional cost)

### Copy tokens
Copy token carries source creature's grpId (not a distinct token grpId). New annotations:

- `TemporaryPermanent` pAnn — first observation, marks EOT-sacrifice tokens
- EOT-sacrifice trigger fires at token ETB (AbilityInstanceCreated immediately)
- If source is adventure creature, copy also gets Adventure proxy object

**Card specs:** electroduplicate
**Existing issue:** #245 (CopyPermanent token subsystem missing)

### PhasedOut / PhasedIn annotations (types 95/96) — NEW
Annotation-only zone change — no ZoneTransfer accompanies phasing.

- **PhasedOut (95):** affectorId = trigger ability instance, affectedIds = [permanent]. No detail keys.
- **PhasedIn (96):** affectedIds = [permanent]. No affectorId (system action). No details.
- Zone change via gameObject diff (zoneId field), not ZoneTransfer annotation.
- Phase-in does NOT re-trigger ETB abilities.

**Card specs:** kaito-shizuki
**Existing rosetta:** types 95/96 listed as MISSING. Now have concrete wire shape.

### CrewedThisTurn annotation (type 94) — NEW
Persistent annotation emitted when crew resolves, cleared at end of turn.

- affectorId = vehicle, affectedIds = [all crew sources]
- Accompanies ModifiedType + LayeredEffect pAnn that makes vehicle a creature

**Card specs:** surgehacker-mech
**Existing rosetta:** type 94 listed as MISSING. Now has concrete wire shape.

### Vehicle / Crew mechanic — NEW
Vehicles are artifacts that become creatures when crewed.

- **Crew activation:** ability on stack (grpId specific to card), PayCostsReq with effectCostType=Select
- **Type change:** ModifiedType + LayeredEffect persistent annotation (effect_id for artifact→creature)
- **ShouldntPlay/RedundantActivation:** server hints redundant crew on already-crewed vehicles

**Card specs:** surgehacker-mech (Crew 4), brute-suit (Crew 1, same session)
**No existing issue.** Needs new issue.

### Channel mechanic — NEW
Hand-zone activated ability. Discard-as-cost uses standard `Discard` category.

- Channel ability has its own grpId (distinct from ETB trigger on same card)
- DamageDealt affectorId uses pre-ObjectIdChanged ID
- Wire-identical to normal discard — only distinguishing signal is coincident AbilityInstanceCreated

**Card specs:** twinshot-sniper
**No existing issue.** Needs catalog entry.

### Saga mechanic — NEW
Enchantment subtype with lore counters (type 108), chapter abilities, and transform on final chapter.

- Lore counter auto-increment on ETB + draw step
- Chapter ability triggers based on counter count (separate grpId per chapter)
- Chapter III: exile-return transform (see Transform section)
- Token creation for opponent (ownerSeatId differs from controllerSeatId)

**Card specs:** tribute-to-horobi
**Existing issue:** #191 (DFC / Saga flip wire)

### Hand-zone activated abilities
Channel requires `ActivationZone$ Hand` — ActionMapper must offer activated abilities
on cards in hand (not just battlefield). Same pattern needed for any hand-activated ability.

**Card specs:** twinshot-sniper (channel), cauldron-familiar (GY zone)

## Tier 3 — New mechanics (card-specific but reusable patterns)

### Adventure lifecycle
Multi-step: CastAdventure (actionType=16) → adventure proxy object (type=Adventure) on stack → resolve to Exile (category=Resolve) → Qualification pAnn (QualificationType=47) → cast creature from Exile (CastSpell zone_src=29).

**Card specs:** ratcatcher-trainee (full loop observed)
**Existing issue:** #173 (Cast adventure)

### Job select (ETB token + auto-attach)
Triggered ability creates token and attaches equipment in one resolution. No player interaction.

**Card specs:** black-mages-rod (single data point), Dragoon's Lance in session 23-10-20 available for variance.

### GY activated abilities
Server offers Activate in GSM actions array when card enters GY. `inactiveActions` for abilities with unmet costs.

**Card specs:** cauldron-familiar (observed offer, return unobserved), thousand-faced-shadow (ninjutsu in inactiveActions)

### Class enchantment leveling
Activated abilities for level-up, all abilities sent upfront, `inactiveActions` with `disqualifyingSourceId` gates progression, ClassLevel pAnn (type=88).

**Card specs:** cleric-class (no mechanics exercised)

### Ninjutsu
Hand→BF attacking (bypasses stack). Swap unblocked attacker back to hand. Offered via `inactiveActions` — JSONL decoder drops this field (tooling gap).

**Card specs:** thousand-faced-shadow (unobserved)

### Sacrifice-as-activated-ability
Distinct from sacrifice-as-cost. Immersturm Predator: sacrifice another creature is the cost (PayCostsReq promptId=1074), then tap + indestructible on resolution. Tap re-fires "whenever tapped" trigger (counter + exile from GY).

**Card specs:** immersturm-predator

### ETB/LTB shared grpId
Cryogen Relic: ETB draw and LTB draw share abilityGrpId 190898. Server disambiguation unknown — may rely on zone context. LTB unobserved.

**Card specs:** cryogen-relic

### Gain control (AllValid targeting)
`GainControl | AllValid$ Rat.token` — "all matching permanents" with no target selection.
Wire shape: ControllerChanged per affected permanent + one LayeredEffectCreated.
Token ownership vs control diverges (ownerSeatId stays original, controllerSeatId flips).

**Card specs:** tribute-to-horobi

### Conditional triggered abilities
Intervening-if conditions ("if you don't control X") are server-side. Trigger suppression
confirmed: no AbilityInstanceCreated when condition fails.

**Card specs:** tatsunari-toad-rider (Keimi check), archfiends-vessel (GY check — unobserved)

### Planeswalker phaseout protection
ETB end-step trigger → PhasedOut → zone 12. Phase-in at next untap (no ETB re-trigger).
loyaltyUsed field tracks activations per turn.

**Card specs:** kaito-shizuki

### Discard-as-cost (channel/additional)
Two patterns: channel (discard self, activated ability from hand) and mandatory additional cost
(discard other card, PayCostsReq promptId=1024). Both use standard `Discard` category.

**Card specs:** twinshot-sniper (channel), mardu-outrider (mandatory additional)

## Existing issues updated

| Issue | Evidence added |
|-------|---------------|
| #191 DFC / Saga flip wire | Treasure Map: grpId mutation confirmed. Tribute to Horobi: exile-return transform with double ObjectIdChanged, categories `Exile`/`Return`, old IDs in Limbo |
| #173 Cast adventure | Ratcatcher Trainee: full lifecycle, actionType=16, Qualification pAnn 47 |
| #177 AbilityWordActive | Kiora: threshold. Brass's: descended. Case: fires at cast, not resolve |
| #192 Mandatory additional cost | Mardu Outrider: PayCostsReq 1024 confirmed. Immersturm: sacrifice PayCostsReq 1074. Surgehacker: crew PayCostsReq Select type |
| #245 CopyPermanent token | Electroduplicate: copy grpId = source grpId, TemporaryPermanent pAnn, Adventure proxy on copy |
| #200 Continuous effects | Tamiyo's Compleation: RemoveAbility+LayeredEffect pAnn shape. Tatsunari: Qualification for evasion |

## New issues needed

| Mechanic | Scope | Priority |
|----------|-------|----------|
| Vehicle / Crew | Type change + crew cost + CrewedThisTurn + RedundantActivation | Medium — NEO staple |
| Channel | Hand-zone activation + discard-as-cost | Low — NEO specific |
| Saga | Lore counters + chapter triggers + transform | Medium — overlaps #191 |
| Phasing (PhasedOut/PhasedIn) | Annotation types 95/96 + zone 12 + GameEventCardPhased wiring | Medium — blocks planeswalkers |

## Trace gaps — next recording targets

| Mechanic | What to play | Why |
|----------|-------------|-----|
| Kicked ETB → GY return | Nullpriest of Oblivion (kick it!) | Confirm "Return" category matches Sun-Blessed Healer |
| GY activation + Food sacrifice | Cauldron Familiar + any Food maker | GY→BF return via activated ability |
| Conditional ETB (from GY) | Archfiend's Vessel + Raise Dead | Self-exile + Demon token creation |
| Class leveling | Cleric Class (reach level 2+) | ClassLevel pAnn, level-up resolution shape |
| Ninjutsu | Thousand-Faced Shadow (activate it!) | Hand→BF attacking, swap action type |
| LTB trigger | Cryogen Relic (sacrifice it) | ETB/LTB disambiguation with shared grpId |
| Transform (saga) | Brass's Tunnel-Grinder (3+ bore counters) | Confirm saga→land transform matches DFC pattern |
| Copy token of adventure creature | Electroduplicate + adventure creature | Adventure proxy on copy token |
| -2 / -7 planeswalker abilities | Kaito Shizuki (activate -2 and -7) | Ninja token creation, emblem zone handling |
| Vehicle ETB with 2+ vehicles | Surgehacker Mech + other vehicle pre-ETB | Confirm X scales (damage=4+) |
| Saga attack trigger | Echo of Death's Wail (sacrifice a creature) | May-sacrifice ability resolution |
| Other channel cards | Boseiju, Otawara (channel lands) | Confirm Discard category consistency |
| Evasion grant variance | Other "can't be blocked by" cards | Confirm Qualification CantBeBlockedByObjects pattern |
