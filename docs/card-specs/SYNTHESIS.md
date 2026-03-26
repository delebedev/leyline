# Card Spec Synthesis — Horizontal Layers

Cross-cut analysis of gaps from all 19 card specs. Each layer is a focused work item
that unblocks multiple cards. Ordered by impact (cards unblocked).

## Tier 1 — Data registration (no logic changes)

### Counter type mapper
Add missing counter type numbers to the mapper.

| Type | Number | Card specs |
|------|--------|-----------|
| INCUBATION | 200 | drake-hatcher |
| LANDMARK | 127 | treasure-map |
| BORE | 182 | brasss-tunnel-grinder |

### Token grpId registry
Register token grpIds so TokenCreated emits the correct ID.

| Token | grpId | Card specs |
|-------|-------|-----------|
| Clue | 89236 | novice-inspector |
| Hero (1/1 white) | 96212 | black-mages-rod |
| Drake (2/2 flying) | 94163 | drake-hatcher |
| Treasure | 87485 | treasure-map |
| Rat (1/1) | 87031 | ratcatcher-trainee |
| Demon (5/5 flying) | ? | archfiends-vessel (unobserved) |

## Tier 2 — Shared protocol handlers

### Transform (silent grpId mutation)
One implementation covers all DFCs. Transform = in-place grpId swap in Diff object, no ZoneTransfer, no dedicated annotation.

**Card specs:** treasure-map, brasss-tunnel-grinder
**Existing issue:** #191 (DFC / Saga flip wire)
**Confirmed pattern:** Treasure Map 87432→87433, Concealing Curtains (prior work). Brass's Tunnel-Grinder unobserved but expected identical.

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

### GY→BF return (category = "Return")
Transfer category **confirmed: "Return"** via Sun-Blessed Healer kicked ETB (sun-blessed-healer spec, gsId 278).

SelectTargetsReq shape: targetSourceZoneId=33 (GY), targetingAbilityGrpId=ability grpId, targetIdx=1, min/maxTargets=1. Fizzle: no legal targets → ability deleted in same diff, no SelectTargetsReq sent.

**Card specs needing this:** nullpriest-of-oblivion, cauldron-familiar, cleric-class, archfiends-vessel, sun-blessed-healer

### PayCostsReq variants
Three distinct PayCostsReq shapes confirmed:

| promptId | Cost type | Card specs |
|----------|-----------|-----------|
| 1024 | Mandatory additional cost (discard) | mardu-outrider |
| 1074 | Sacrifice-as-cost (activated ability) | immersturm-predator |
| 1010 | Equip targeting (SelectTargetsReq) | black-mages-rod (standard equip) |

**Existing issue:** #192 (Mandatory additional cost)

### Copy tokens
Copy token carries source creature's grpId (not a distinct token grpId). New annotations:

- `TemporaryPermanent` pAnn — first observation, marks EOT-sacrifice tokens
- EOT-sacrifice trigger fires at token ETB (AbilityInstanceCreated immediately)
- If source is adventure creature, copy also gets Adventure proxy object

**Card specs:** electroduplicate
**No existing issue.** → create one.

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

## Existing issues updated

| Issue | Evidence added |
|-------|---------------|
| #191 DFC / Saga flip wire | Treasure Map: grpId mutation confirmed, counter removal + 3 tokens atomic |
| #173 Cast adventure | Ratcatcher Trainee: full lifecycle, actionType=16, Qualification pAnn 47 |
| #177 AbilityWordActive | Kiora: threshold. Brass's: descended. Case: fires at cast, not resolve |
| #192 Mandatory additional cost | Mardu Outrider: PayCostsReq 1024 confirmed. Immersturm: sacrifice PayCostsReq 1074 |

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
