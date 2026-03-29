# Card Spec Synthesis — Horizontal Layers

Cross-cut analysis of gaps from all 35 card specs. Each layer is a focused work item
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
| Rabbit (1/1 white) | 94160 | hare-apparent |
| Demon (5/5 flying) | ? | archfiends-vessel (unobserved) |

### Keyword grpId registry
Complete keyword grpId table. Confirmed from wire.

| grpId | Keyword | Confirmed by |
|-------|---------|-------------|
| 6 | First Strike | angelic-destiny (AddAbility pAnn); sire-of-seven-deaths |
| 8 | Flying | angelic-destiny (AddAbility pAnn grpid=8) |
| 9 | Haste | controllerchanged-wire (Claim the Firstborn) |
| 12 | Lifelink | sire-of-seven-deaths uniqueAbilities |
| 13 | Reach | wildborn-preserver uniqueAbilities; sire-of-seven-deaths |
| 14 | Trample | overrun AddAbility pAnn; mossborn-hydra native; sire-of-seven-deaths |
| 15 | Vigilance | **First confirmation.** sire-of-seven-deaths (only card with native Vigilance) |
| 142 | Menace | concealing-curtains Qualification pAnn; nullpriest; sire-of-seven-deaths |
| 175751 | Ward (Sire) | sire-of-seven-deaths wire (card-specific ability grpId — ward grpIds vary per card) |

`KeywordQualifications.kt` — add grpId=15 (Vigilance), confirm grpId=14 (Trample; was commented out with `?`). Note: ward abilityGrpId is per-card (Cackling Prowler=141939, Sire=175751), not a universal grpId.

### Zone 12 = phaseout zone
Kaito spec confirms zone 12 is the phaseout zone. Not in the standard zones array — permanents
move here when phased out, return to Battlefield on phase-in. Add to ZoneIds constants and
zone tracker.

**Card specs:** kaito-shizuki

### Zone 37 = P2 Graveyard
Surgehacker spec sends dead creature to zone 37. This is seat 2's graveyard (per-seat zone).
Already in rosetta Table 3 but the spec flagged it as unknown — confirmed.

---

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
| Morbid (boolean, no value/threshold) | **fires at creature death** | cackling-prowler |

**Correction from Case spec:** AbilityWordActive fires at **cast time** (on stack), not at resolve. Value resets at NewTurnStarted. Solving is silent — no discrete annotation, just value meeting threshold.

**Morbid variant (turn-scoped, boolean):** no `value`, `threshold`, or `AbilityGrpId` — only `AbilityWordName: "Morbid"`. Fires in the same diff as `ZoneTransfer(SBA_Damage)` for the dying creature, not at end-step entry. `affectorId=1` (player seat). `affectedIds` lists all morbid cards currently in play (including hand) and adds the ability iid while on stack. Same pattern confirmed for Raid. Wire spec: `docs/card-specs/cackling-prowler.md`.

**Two AbilityWordActive patterns:**
- **Value-tracking:** Threshold, Case — carry `value`, `threshold`, `AbilityGrpId`; update every relevant diff
- **Boolean/turn-scoped:** Morbid, Raid, Descended — presence flag only; fire on triggering event; no numeric fields

**ImmediateTrigger "ValueOfX" variant:** AbilityWordActive with `AbilityWordName="ValueOfX"`, `value=X_paid`, `AbilityGrpId=<inner trigger grpId>`. Lives on the inner trigger instance (not the source permanent). Created when inner trigger is placed on stack, deleted at resolution. X=0 path still fires with `value=0`. See wildborn-preserver spec.

**Existing issue:** #177 (AbilityWordActive annotation not emitted)

### Qualification annotation (type 42)
Persistent annotation for continuous effects and ability restrictions.

| QualificationType | Context | Card specs |
|-------------------|---------|-----------|
| 47 | Adventure exile marker (enables cast-from-exile) | ratcatcher-trainee |
| 21 | "Loses all abilities + doesn't untap" (aura continuous effect) | tamiyos-compleation |
| CantBeBlockedByObjects (282) | Evasion grant ("can't be blocked except by...") | tatsunari-toad-rider |
| 40 | Menace blocking restriction (grpid=142) | concealing-curtains, nullpriest, **sire-of-seven-deaths** |

**Key findings:**
- QualificationType=40 (Menace): fires at ETB for any creature with Menace. Pattern: `affectorId=creatureIid, affectedIds=[creatureIid], grpid=142, QualificationSubtype=0, SourceParent=creatureIid`. Confirmed on 3 cards.
- Only Menace fires a Qualification pAnn from the standard keyword set. First Strike, Vigilance, Trample, Reach, Lifelink, Ward — encoded only via uniqueAbilities.
- TargetSpec also uses type 42 — `index=1/2`, `abilityGrpId`, `affectorId=stackObject`, persists while spell is on stack.

**Card specs:** tamiyos-compleation, tatsunari-toad-rider, ratcatcher-trainee, sire-of-seven-deaths

### RemoveAbility annotation (type 23)
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

**Category "Return" also applies to bounce (BF→Hand)** — Angelic Destiny host bounce at
gsId 463: `category="Return"`. The term "Return" covers all non-standard zone-to-zone
movements that restore a card to a prior zone. **"Bounce" is not a real server category.**
Rosetta Table 2 row 32 shows `Bounce` as an Arena category name — this is a leyline internal
alias. The wire string is always `"Return"`.

SelectTargetsReq shape: targetSourceZoneId=33 (GY), targetingAbilityGrpId=ability grpId, targetIdx=1, min/maxTargets=1. Fizzle: no legal targets → ability deleted in same diff, no SelectTargetsReq sent.

**Card specs needing this:** nullpriest-of-oblivion, cauldron-familiar, cleric-class, archfiends-vessel, sun-blessed-healer, tribute-to-horobi

### PayCostsReq variants
Five distinct PayCostsReq shapes confirmed:

| promptId | Cost type | Card specs |
|----------|-----------|-----------|
| 1024 | Mandatory additional cost (discard) | mardu-outrider |
| 1074 | Sacrifice-as-cost (activated ability) | immersturm-predator |
| 1010 | Equip targeting (SelectTargetsReq) | black-mages-rod (standard equip) |
| 11 | ImmediateTrigger pay-X cost | wildborn-preserver |
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

### PhasedOut / PhasedIn annotations (types 95/96)
Annotation-only zone change — no ZoneTransfer accompanies phasing.

- **PhasedOut (95):** affectorId = trigger ability instance, affectedIds = [permanent]. No detail keys.
- **PhasedIn (96):** affectedIds = [permanent]. No affectorId (system action). No details.
- Zone change via gameObject diff (zoneId field), not ZoneTransfer annotation.
- Phase-in does NOT re-trigger ETB abilities.

**Card specs:** kaito-shizuki
**Existing rosetta:** types 95/96 listed as MISSING. Now have concrete wire shape.

### CrewedThisTurn annotation (type 94)
Persistent annotation emitted when crew resolves, cleared at end of turn.

- affectorId = vehicle, affectedIds = [all crew sources]
- Accompanies ModifiedType + LayeredEffect pAnn that makes vehicle a creature

**Card specs:** surgehacker-mech
**Existing rosetta:** type 94 listed as MISSING. Now has concrete wire shape.

### Vehicle / Crew mechanic
Vehicles are artifacts that become creatures when crewed.

- **Crew activation:** ability on stack (grpId specific to card), PayCostsReq with effectCostType=Select
- **Type change:** ModifiedType + LayeredEffect persistent annotation (effect_id for artifact→creature)
- **ShouldntPlay/RedundantActivation:** server hints redundant crew on already-crewed vehicles

**Card specs:** surgehacker-mech (Crew 4), brute-suit (Crew 1, same session)
**No existing issue.** Needs new issue.

### Channel mechanic
Hand-zone activated ability. Discard-as-cost uses standard `Discard` category.

- Channel ability has its own grpId (distinct from ETB trigger on same card)
- DamageDealt affectorId uses pre-ObjectIdChanged ID
- Wire-identical to normal discard — only distinguishing signal is coincident AbilityInstanceCreated

**Card specs:** twinshot-sniper
**No existing issue.** Needs catalog entry.

### Saga mechanic
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

### ImmediateTrigger / "may pay {X}" pattern
Two-stage trigger wire: outer trigger (grpId=136114 on WP) fires on non-Human ETB, then immediately
sends OptionalActionMessage → NumericInputReq (ChooseX_ad80) → PayCostsReq → inner trigger
(grpId=181759) placed on stack. These are two distinct ability instances with distinct grpIds.

Key wire shapes:
- **OptionalActionMessage:** `promptId=1159`, `sourceId`=outer trigger iid, `CardId` param=Preserver iid, `allowCancel: No_a526`
- **NumericInputReq:** `promptId=51`, `numericInputType=ChooseX_ad80`, `maxValue=INT_MAX` (server does NOT pre-clamp), `stepSize=1`
- **PayCostsReq:** `promptId=11`, `manaCost.objectId`=outer trigger iid. New promptId variant.
- **AbilityWordActive "ValueOfX":** pAnn on inner trigger instance (`affectorId=affectedId=inner iid`), `AbilityGrpId=181759`, `value=X_paid`. Appears at inner trigger creation, deleted at resolution.
- **X=0 is legal** — full wire sequence still fires with PayCostsReq `{0}` and CounterAdded `transaction_amount=0`.
- **Multiple simultaneous ETBs** — each non-Human ETB generates an independent trigger lifecycle.
  Three distinct instances confirmed in one turn.

**Card specs:** wildborn-preserver
**No existing issue.** Needs new issue.

### OrderReq (GRE type 17) — simultaneous trigger ordering
**NEW from hare-apparent.** `OrderReq` fires when multiple triggers from the same player become
pending simultaneously (e.g., two Hare Apparents entering via Raise the Past).

Wire shape:
```
type: OrderReq_695e
systemSeatIds: 1
msgId: N
gameStateId: G
prompt {
  promptId: 91
  parameters { parameterName: "CardId", type: Number, numberValue: <causing_spell_iid> }
}
orderReq {
  ids: <permanent_iid_1>   // source creatures (not ability instances)
  ids: <permanent_iid_2>
}
allowCancel: No_a526
```

**Two-prompt sequence for simultaneous triggers:**
1. **OrderReq (type 17)** — fires in the same `GreToClientEvent` as the causing spell's resolution diff. `ids` are **permanent iids** (source creatures). Ability instances do not exist yet. `promptId=91`.
2. **SelectNreq(Stacking)** — fires after ability instances materialize in Pending zone (25). `ids` are **ability instance iids**. `context: TriggeredAbility_c799`, `optionContext: Stacking`, `validationType: NonRepeatable`, `promptId=23`.

**Key distinction:** OrderReq uses permanent-level iids (pre-ability-creation), SelectNreq(Stacking) uses ability-instance iids. Both prompts are sent for simultaneous trigger scenarios.

**Rosetta Table 5:** OrderReq (type 17) listed as `-- (MISSING)`. First wire data with full shape.
**Existing issue:** #76 (Wire OrderReq and duplicate-safe GroupResp mapping)

**Card specs:** hare-apparent (T12 Raise the Past returning two Hare Apparents)

### SBA_UnattachedAura (category "SBA_UnattachedAura")
When an aura's enchanted permanent leaves the battlefield by any non-SBA route, the server emits:

1. `LayeredEffectDestroyed` per static continuous effect on the aura (one per effect_id). `affectorId=aura_iid` (pre-rename). Count matches LayeredEffectCreated count at attachment.
2. `ObjectIdChanged` (no affectorId) + `ZoneTransfer(SBA_UnattachedAura, zone_dest=33)` (no affectorId).

**Ordering rules (confirmed across 7 events, 3 trigger paths):**
- **Inline with host Destroy:** if host dies via mass removal, that host's aura `LayeredEffectDestroyed` may fire inline within the Destroy sequence (before ResolutionComplete). SBA ZoneTransfer still fires after ResolutionComplete.
- **After ResolutionComplete:** the `ObjectIdChanged` + `ZoneTransfer(SBA_UnattachedAura)` always fires after `ResolutionComplete` and spell GY `ZoneTransfer(Resolve)`.
- **afforId is absent** on both ObjectIdChanged and ZoneTransfer — consistent with all SBA categories.
- **aura destination is always zone 33 (P1 GY)** or zone 37 (P2 GY) depending on owner. Never hand.

**LayeredEffectDestroyed count = number of static effects on the aura:**
- River's Favor (1 static) → 1 LayeredEffectDestroyed
- Knight's Pledge (P/T pump only) → 1 LayeredEffectDestroyed
- Pacifism (restriction ability, no P/T layer) → 0 LayeredEffectDestroyed
- Angelic Destiny (3 static lines) → 3 LayeredEffectDestroyed (effect_ids 7003, 7004, 7005)

**Angelic Destiny return trigger is Destination$-gated:** `Destination$ Graveyard` in trigger means bounce (BF→Hand, category=`Return`) does NOT fire the return trigger. Wire confirmed: no AbilityInstanceCreated on bounce. SBA_UnattachedAura only.

**Existing issue:** #170 (SBA deaths use wrong TransferCategory). Also tracked in `sba-unattached-aura-wire.md`.

**Card specs:** angelic-destiny, day-of-judgment (4 wipes), plus 2026-03-22_23-20-47 (combat death)

### Mass removal (board wipe) annotation ordering
`DestroyAll` resolution follows a fixed annotation sequence:

```
ResolutionStart
  [ObjectIdChanged + ZoneTransfer(Destroy)] × N   (may interleave LayeredEffectDestroyed for enchanted creature's aura)
ResolutionComplete
ObjectIdChanged + ZoneTransfer(Resolve)            spell → GY
TokenDeleted × T                                   token cleanup
LayeredEffectDestroyed × E (per remaining aura)
ObjectIdChanged + ZoneTransfer(SBA_UnattachedAura) × A
```

**TokenDeleted timing:** fires after `ZoneTransfer(Resolve)` for the spell — not inline with Destroy ZoneTransfers. Token's ZoneTransfer(Destroy) fires inline, then TokenDeleted fires in the post-ResolutionComplete SBA block.

**Annotation ID gaps** observed (e.g., ids 457 and 460 absent in one diff) — server-internal IDs consumed by non-client-visible operations. Not a JSONL artifact. No wire impact.

**Card specs:** day-of-judgment (sessions 2026-03-29_16-45-39 and 2026-03-29_16-55-19)

### Keyword-granting spell (team-wide AddAbility)
Overrun confirms the multi-creature keyword grant wire shape (issue #31).

**Two LayeredEffects per spell cast** — one for P/T layer, one for keyword layer. Both have `affectorId = spell instanceId`.

**AddAbility+LayeredEffect pAnn for team grant:**
- `affectedIds` = flat list of all pumped creature iids
- `UniqueAbilityId` = one value per affected creature (repeated int field)
- `grpid` = keyword grpId (14 for Trample)
- `effect_id` = second LayeredEffectCreated transient's id
- `originalAbilityObjectZcid` = spell instanceId

**Creature gameObject update:** each pumped creature's gameObject in the resolution diff gains a new `uniqueAbilities { id: N; grpId: 14 }` entry. Removed at EOT when LayeredEffectDestroyed fires.

**EOT cleanup:** both LayeredEffectDestroyed fire simultaneously in the same diff as `PhaseOrStepModified` (end step). `affectorId = spell instanceId` on both.

**Scales linearly:** 3 creatures → 3 UniqueAbilityId values, 3 PowerToughnessModCreated; 4 creatures → 4 of each. LayeredEffectCreated count is always 2.

**Card specs:** overrun (2 sessions, 3 and 4 creatures)
**Existing issue:** #31 (keyword-granting spells)

### OptionalActionMessage — ETB replacement effects (shock lands)
Shock lands use `OptionalActionMessage` to offer the "pay 2 life or enter tapped" choice.

Wire shape (gsId N+1, before resolution):
```
gameObject { instanceId: <new_iid>, grpId: 68739, zoneId: 0 }  // staging/limbo
persistentAnnotations {
  type: ReplacementEffect (type 62)
  affectorId: 9002   // system seat affector (not stable across plays)
  affectedIds: [<new_iid>]
  details: { grpid: 90846, ReplacementSourceZcid: <hand_iid> }
}
OptionalActionMessage {
  sourceId: 9002   // same system affector
  prompt { promptId: 2233, parameters[0]: { CardId: <new_iid> } }
  allowCancel: No_a526
}
```

Resolution diff (gsId N+2):
- **Pay path (accept):** `SyntheticEvent { type: 1 }` + `ModifiedLife { life: -2 }` + `ZoneTransfer(PlayLand)` with `isTapped` absent. ReplacementEffect pAnn deleted.
- **Decline path:** `ZoneTransfer(PlayLand)` with `isTapped: true` on gameObject. No SyntheticEvent or ModifiedLife.

**Disambiguation gap:** both accept and decline send identical empty `OptionalActionResp`. The server infers choice from some signal not visible in JSONL. Raw C-S proto inspection needed.

**abilityGrpId 90846** = shock land replacement effect. Consistent across both Temple Garden plays; likely universal for all 10 Ravnica shock lands.

**ColorProduction pAnn (type 110):** added at ETB with `colors: [1, 5]` for White+Green. First confirmed shock land color production observation.

**Existing rosetta:** type 62 (ReplacementEffect) listed as MISSING. Now has concrete wire shape.

**Card specs:** temple-garden (2 plays: accept gsId 25, decline gsId 160)
**No existing issue.** New mechanic, needs issue.

### Bite-down / fight — two-group SelectTargetsReq
Confirmed same wire shape as Bushwhack fight: both target groups in a single SelectTargetsReq message, not sequenced. Both groups share `targetingAbilityGrpId` (sub-ability). The outer `abilityGrpId` on the req is the spell grpId.

**DamageDealt affectorId semantics:** for fight-like effects where "your creature deals damage to their creature," `affectorId` = the controller's creature iid (not the spell iid). Spell orchestrates; creature is the damage source. Confirmed: Bite Down affectorId=346 (Dog), not 423 (spell).

**One-directional vs. fight:** Bite Down differs from Fight — target does NOT deal back. Only one DamageDealt annotation fires (dealer → target). SBA_Damage co-fires in same diff if lethal.

**Card specs:** bite-down (session 2026-03-29_17-04-26)

---

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

### Ward — pay-life variant
Sire of Seven Deaths has `K:Ward:PayLife<7>`. abilityGrpId=175751. Ward not exercised in session
(opponent never targeted). Same PayCostsReq protocol as ward {2} (from ward-counter-wire) but
with life-payment effectCostType. Ward abilityGrpIds are per-card (not universal).

**Card specs:** sire-of-seven-deaths, cackling-prowler (ward {2}, unexercised)

---

## Counter semantics (consolidated)

### delta vs. total
`CounterAdded.transaction_amount` = delta added (not new total). For `MultiplyCounter` (Forge DSL), delta = current count at trigger time. Progression: 1→+1→2→+2→4→+4→8→+8→16. The persistent `Counter` pAnn `count` field is always the running total.

`LayeredEffectDestroyed` + `LayeredEffectCreated` fires on every counter change that modifies P/T. ETB counter: only `LayeredEffectCreated` (no prior effect). Subsequent changes: always the destroy+create pair. Effect IDs are monotonically increasing but non-sequential (gaps from other effects sharing the ID space).

**Counter pAnn affectorId = 4002** — constant across all counter updates (not the spell/ability iid). Appears to be a server-side constant for counter-based P/T modification.

**Card spec:** mossborn-hydra

### Morbid counter resolution shape
`CounterAdded` + `PowerToughnessModCreated` + `LayeredEffectCreated` all in one diff at end-step resolution. `ModifiedToughness+ModifiedPower+Counter` pAnn created with `count=1, counter_type=1`. affectorId on CounterAdded = the trigger ability iid (not the permanent).

**Card spec:** cackling-prowler

---

## AssignDamageReq player slot (issue #235)

**Root cause confirmed:** defending player's `instanceId` in `AssignDamageReq.assignments` = `seatId` directly (value 2 for seat 2). Not a synthetic ID, not 0.

Wire shape (Mossborn Hydra, gsId 184, T8 trample attack):
```
damageAssigners {
  instanceId: 303           # attacker
  totalDamage: 2
  assignments { instanceId: 316; minDamage: 1; assignedDamage: 1 }   # blocker
  assignments { instanceId: 2;   maxDamage: 1;  assignedDamage: 1 }  # player = seatId
  decisionPrompt { promptId: 8; parameters[0]: CardId=303 }
}
```
`DamageDealt` to player: `affectedIds=[2]` (seatId as target instanceId).

Fix: wire `seatId` as the player assignment slot in `CombatHandler.sendAssignDamageReq`.

**Card spec:** mossborn-hydra

---

## Existing issues updated

| Issue | Evidence added |
|-------|---------------|
| #191 DFC / Saga flip wire | Treasure Map: grpId mutation confirmed. Tribute to Horobi: exile-return transform with double ObjectIdChanged, categories `Exile`/`Return`, old IDs in Limbo |
| #173 Cast adventure | Ratcatcher Trainee: full lifecycle, actionType=16, Qualification pAnn 47 |
| #177 AbilityWordActive | Kiora: threshold. Brass's: descended. Case: fires at cast, not resolve. Cackling Prowler: Morbid boolean-only (no value/threshold). Wildborn Preserver: ValueOfX on inner trigger |
| #192 Mandatory additional cost | Mardu Outrider: PayCostsReq 1024 confirmed. Immersturm: sacrifice PayCostsReq 1074. Surgehacker: crew PayCostsReq Select type. Wildborn Preserver: PayCostsReq promptId=11 |
| #245 CopyPermanent token | Electroduplicate: copy grpId = source grpId, TemporaryPermanent pAnn, Adventure proxy on copy |
| #200 Continuous effects | Tamiyo's Compleation: RemoveAbility+LayeredEffect pAnn shape. Tatsunari: Qualification for evasion. Angelic Destiny: 3× LayeredEffect for multi-static aura |
| #235 AssignDamageReq defender slot | Mossborn Hydra: player instanceId = seatId confirmed. Fix: wire seatId in CombatHandler.sendAssignDamageReq |
| #170 SBA_UnattachedAura | Day of Judgment: 4 additional wipes, full ordering spec. Angelic Destiny: 3× LayeredEffectDestroyed for 3-effect aura, bounce path confirmed. Pacifism: 0× LayeredEffectDestroyed for restriction-only aura |
| #31 Keyword-granting spells | Overrun: team-wide AddAbility pAnn shape fully documented. trample grpId=14 confirmed. Angelic Destiny: multi-keyword pack (Flying+FirstStrike in single AddAbility pAnn) |
| #76 OrderReq / duplicate-safe GroupResp | Hare Apparent: OrderReq type 17 full wire shape. permanent-iids before ability-creation, promptId=91. Followed by SelectNreq(Stacking) promptId=23 |

---

## New issues needed

| Mechanic | Scope | Priority |
|----------|-------|----------|
| Vehicle / Crew | Type change + crew cost + CrewedThisTurn + RedundantActivation | Medium — NEO staple |
| Channel | Hand-zone activation + discard-as-cost | Low — NEO specific |
| Saga | Lore counters + chapter triggers + transform | Medium — overlaps #191 |
| Phasing (PhasedOut/PhasedIn) | Annotation types 95/96 + zone 12 + GameEventCardPhased wiring | Medium — blocks planeswalkers |
| ImmediateTrigger / pay-X via trigger | OptionalActionMessage + NumericInputReq + inner ImmediateTrigger wire | Medium — Wildborn Preserver, any "may pay {X}" triggered ability |
| Shock land ETB replacement | OptionalActionMessage promptId=2233, ReplacementEffect pAnn, ETB-tapped vs life-loss path, C-S disambiguation | Medium — all 10 shock lands blocked |
| Keyword grpId registration | Add grpId=15 (Vigilance), confirm grpId=14 (Trample) in KeywordQualifications.kt | Low — data only |
| Menace Qualification pAnn | QualificationType=40, grpid=142 at ETB for any Menace creature | Medium — client blocking enforcement |
| ColorProduction pAnn (type 110) | Multi-color lands need colors=[N,M] list; currently may emit single value | Low — visual only |

---

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
| OrderReq with 3+ triggers | Hare Apparent with 3 entering simultaneously | Confirm ids grows linearly; confirm OrderReq skipped for single trigger |
| Ward pay-life | Sire of Seven Deaths (opponent targets it) | PayCostsReq life-payment effectCostType shape |
| Shock land C-S accept/decline | Raw proto decode of OptionalActionResp | Confirm empty vs. typed field; close disambiguation gap |
| OptionalActionMessage — ImmediateTrigger decline | Wildborn Preserver (decline the X payment) | Confirm decline path for promptId=1159 |
