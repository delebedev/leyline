# Card Spec Synthesis — Horizontal Layers

Cross-cut analysis of gaps from all card specs. Each layer is a focused work item
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

**Card specs:** treasure-map, brasss-tunnel-grinder, (ratcatcher-trainee uses adventure→exile, not transform)
**Existing issue:** #210 (DFC / Saga flip wire)
**Confirmed pattern:** Treasure Map 87432→87433, Concealing Curtains (prior work). Brass's Tunnel-Grinder unobserved but expected identical.

### Cast-from-non-hand
ActionMapper must offer cast actions for cards in zones other than Hand.

| Origin zone | Action type | Category | Card specs |
|-------------|------------|----------|-----------|
| GY (33) | CastSpell (flashback) | CastSpell | think-twice |
| GY (33) | CastAdventure (from GY) | CastSpell | (Mosswood Dreadknight — not yet spec'd) |
| Exile (29) | CastSpell (adventure creature) | CastSpell | ratcatcher-trainee |

Wire shape is identical to hand cast — only zone_src differs. The server uses same CastSpell category for all origins.
**Existing issue:** #213 (Cast adventure: 5 code gaps)

### AbilityWordActive annotation
Persistent annotation tracking ability word conditions (threshold, descended, etc.).

| Word | Card specs |
|------|-----------|
| Threshold (value=GY count, threshold=7) | kiora-the-rising-tide |
| Descended (no value/threshold fields) | brasss-tunnel-grinder |

**Existing issue:** #215 (AbilityWordActive annotation not emitted)

### GY→BF return (category = "Return")
Transfer category **confirmed: "Return"** via Sun-Blessed Healer kicked ETB (sun-blessed-healer spec, gsId 278).

SelectTargetsReq shape: targetSourceZoneId=33 (GY), targetingAbilityGrpId=ability grpId, targetIdx=1, min/maxTargets=1. Fizzle: no legal targets → ability deleted in same diff, no SelectTargetsReq sent.

**Card specs needing this:** nullpriest-of-oblivion, cauldron-familiar, cleric-class, archfiends-vessel, sun-blessed-healer

### Mandatory additional cost (PayCostsReq)
Distinct from CastingTimeOptionsReq (optional costs) and SelectNReq (hand-size discard).

**Card specs:** mardu-outrider
**Wire:** PayCostsReq promptId=1024, EffectCostResp empty, Discard ZoneTransfer with spell iid as affectorId.
**Existing issue:** #220 (Mandatory additional cost)

## Tier 3 — New mechanics (card-specific but reusable patterns)

### Adventure lifecycle
Multi-step: CastAdventure (actionType=16) → adventure proxy object (type=Adventure) on stack → resolve to Exile (category=Resolve) → Qualification pAnn (QualificationType=47) → cast creature from Exile (CastSpell zone_src=29).

**Card specs:** ratcatcher-trainee (full loop observed)
**Existing issue:** #213 (Cast adventure)

### Job select (ETB token + auto-attach)
Triggered ability creates token and attaches equipment in one resolution. No player interaction.

**Card specs:** black-mages-rod (single data point), Dragoon's Lance in session 23-10-20 available for variance.
**No existing issue.** → create one.

### GY activated abilities
Server offers Activate in GSM actions array when card enters GY. `inactiveActions` for abilities with unmet costs.

**Card specs:** cauldron-familiar (observed offer, return unobserved)
**No existing issue.** → create one.

### Class enchantment leveling
Activated abilities for level-up, all abilities sent upfront, `inactiveActions` with `disqualifyingSourceId` gates progression, ClassLevel pAnn (type=88).

**Card specs:** cleric-class (no mechanics exercised)
**No existing issue.** → create one.

### Ninjutsu
Hand→BF attacking (bypasses stack). Swap unblocked attacker back to hand.

**Card specs:** thousand-faced-shadow (pending)
**No existing issue.** → create one.

## Existing issues to update

| Issue | New evidence from card specs |
|-------|----------------------------|
| #210 DFC / Saga flip wire | Treasure Map: grpId mutation confirmed, counter removal + 3 tokens atomic |
| #213 Cast adventure | Ratcatcher Trainee: full lifecycle, actionType=16, Qualification pAnn 47 |
| #215 AbilityWordActive | Kiora: threshold tracking. Brass's: descended (no value fields) |
| #220 Mandatory additional cost | Mardu Outrider: PayCostsReq shape confirmed |

## New issues to create

1. **Job select wire** — ETB token + auto-attach. Refs: black-mages-rod spec.
2. **GY activated abilities** — Activate from GY zone, inactiveActions for unmet costs. Refs: cauldron-familiar spec.
3. **Class enchantment leveling** — level-up activation, ClassLevel pAnn, disqualifyingSourceId. Refs: cleric-class spec.
4. **Ninjutsu** — Hand→BF attacking, attacker swap. Refs: thousand-faced-shadow spec (pending).
5. **GY→BF transfer category** — unobserved across all specs. Needs dedicated recording.
