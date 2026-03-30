# Card Spec Synthesis — Horizontal Layers

Cross-cut analysis of gaps from all 31 card specs. Each layer is a focused work item
that unblocks multiple cards. Ordered by impact (cards unblocked).

**Updated:** 2026-03-30 (batch: kellan, scavenging-ooze, spinner-of-souls, loot, archmage-of-runes, homunculus-horde)

## Tooling feedback tally

| Feature | Votes | Specs | Status |
|---------|-------|-------|--------|
| Card name resolution in trace affector/affected IDs | 4 | scavenging-ooze, spinner-of-souls, loot, homunculus-horde | **Done** — card name enrichment landed (archmage confirms it works) |
| `gsm diff <N> <M>` — compare two GSMs | 3 | scavenging-ooze, loot, archmage-of-runes | Open |
| `trace --json` flag | 3 | kellan, scavenging-ooze, loot | **Partial** — `--json` works on `gsm show` and `trace --gsid`, but `trace --json` alone produces unparseable output (spinner report) |
| `trace --filter` by annotation type | 2 | scavenging-ooze, loot | Open |
| abilityGrpId -> ability text lookup (`scry ability <id>`) | 3 | kellan, spinner-of-souls, loot | **Done** — `just scry-ts ability <id>` works (archmage, homunculus confirm) |
| `gsm show` saved-game support | 1 | kellan (session 1) | **Done** — fixed between Kellan sessions 1 and 2 |
| Opponent zone labeling (ours/theirs) in trace | 3 | scavenging-ooze, spinner-of-souls, loot | Open |
| GRE message capture (OptionalActionMessage, OrderReq, etc.) | 1 | spinner-of-souls | Open |
| `trace --json` fix (broken with `--gsid`) | 1 | spinner-of-souls | Open — produces unparseable output |
| Zone contents query (`gsm show --zone`) | 1 | spinner-of-souls | Open |
| `trace --objects` (gameObject diffs alongside annotations) | 1 | homunculus-horde | Open |
| `gsm show --instance <iid>` (filter to specific instanceId) | 1 | homunculus-horde | Open |
| `gsm show --actions` (actions only, skip zones/annotations) | 1 | archmage-of-runes | Open |
| `scry actions --instanceId` (actions for a permanent across GSMs) | 1 | kellan (session 2) | Open |
| `scry annotations --type <T>` (cross-card annotation search) | 1 | kellan (session 1) | Open |

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
| STUN | ? | cryogen-relic (unobserved — number unknown) |

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
| Scion of the Deep (8/8 blue Octopus) | ? | kiora-the-rising-tide (unobserved) |

### Zone 12 = phaseout zone

Kaito spec confirms zone 12 is the phaseout zone. Not in the standard zones array — permanents
move here when phased out, return to Battlefield on phase-in. Add to ZoneIds constants and
zone tracker.

**Card specs:** kaito-shizuki

### Zone 37 = P2 Graveyard

Surgehacker spec sends dead creature to zone 37. This is seat 2's graveyard (per-seat zone).
Already in rosetta Table 3 but the spec flagged it as unknown — confirmed.

## Tier 2 — Shared protocol handlers

### OptionalActionMessage handler (NEW)

The "you may" trigger pattern requires sending an `OptionalActionMessage` after `ResolutionStart` (with `pendingMessageCount=1`) and handling `OptionalResp`. If the player declines, emit `AbilityInstanceDeleted` without resolution. Horizontal blocker — any "may" triggered ability needs it.

**Unblock count: 3+** (spinner-of-souls DigUntil, tribute-to-horobi attack sacrifice, any future "may" trigger)

**Card specs:** spinner-of-souls
**No existing issue.** Needs new issue.

### Static cost reduction in action builder (NEW)

The action mapper must query Forge's cost adjustment system when generating Cast actions, so `manaCost` reflects reduced costs. No CostReduction annotation exists in the wire — the offered action simply has the reduced cost baked in. When generic cost is reduced to 0, the `ManaColor_Generic` entry disappears from the array entirely. Flashback and alternative costs also get reduced.

**Card specs:** archmage-of-runes
**No existing issue.** Needs new issue.

### CopyPermanent token creation (NEW — expanded)

The copy token must clone grpId, overlayGrpId, objectSourceGrpId, power, toughness, color, cardTypes, subtypes, and uniqueAbilities from the source permanent. Must set `isCopy: true` and `type: GameObjectType_Token`. Copy tokens inherit triggered abilities and fire them independently. parentId on copy tokens = the ability instance iid that created it (not the source permanent).

**Unblock count: 3** (homunculus-horde self-copy, electroduplicate, thousand-faced-shadow ETB)

**Card specs:** homunculus-horde, electroduplicate, thousand-faced-shadow
**Existing issue:** #245 (CopyPermanent token subsystem missing)

### "Draw your second card" trigger (NEW)

Draw-count tracking per turn per player is prerequisite infrastructure. When the Nth draw fires, any "draw your Nth card" triggers must emit AbilityInstanceCreated. Each copy of the source permanent (including copy tokens) independently fires.

**Card specs:** homunculus-horde
**No existing issue.** Needs new issue (or extend #245 scope).

### AdjustLandPlays static effect (NEW)

No existing support for modifying per-turn land play count. Need to: track base land plays (1) + adjustments per player per turn, decrement remaining count after each PlayLand, only offer `ActionType_Play` for lands when remaining count > 0, reset at turn start. The server communicates this implicitly — the actions array simply continues to include PlayLand entries.

**Card specs:** loot-exuberant-explorer
**No existing issue.** Needs new issue.

### Dig ability (NEW)

The `AB$ Dig` pattern — look at top N, choose up to M matching a filter, put to a zone, rest to bottom. Components: library peek (top N), filter by `ChangeValid` (creature type + CMC comparison), optional selection, zone transfer with category=`Put` (not CastSpell), bottom-of-library placement + shuffle (`RestRandomOrder`). The server resolves it internally — no cards are revealed to the opponent, no annotations expose the looked-at set.

**Card specs:** loot-exuberant-explorer
**No existing issue.** Related to but distinct from DigUntil.

### DigUntil resolution (NEW)

Reveal cards from library until finding one matching a filter. Create `GameObjectType_RevealedCard` objects for all revealed cards, move the found card to destination zone, shuffle the rest. Forge handles this via `DigUntilEffect.java`. RevealedCardCreated fires during resolution; RevealedCardDeleted fires when the underlying card changes zones later.

**Card specs:** spinner-of-souls
**No existing issue.** Needs new issue.

### RevealedCardCreated / RevealedCardDeleted lifecycle (NEW)

New annotation types and game object type. RevealedCard objects have their own instanceIds, distinct from the real cards, carry full card data for client rendering. Created during DigUntil / library search / reveal resolution. Persist until the underlying card changes zones.

**Unblock count: 5+** (spinner-of-souls, bushwhack, duress, concealing-curtains, ashnods-intervention)

**Card specs:** spinner-of-souls, bushwhack, duress, concealing-curtains, ashnods-intervention
**No existing issue.** Needs new issue.

### ShouldntPlay annotation (type 53) (NEW — expanded)

Four reasons now documented:
- `EntersTapped` — previously known
- `Legendary` — kellan, loot (affectedIds grows with copies in hand)
- `ConsequentialConditionNotMet` — kellan (ability-ladder gate, carries abilityGrpId)
- `RedundantActivation` — surgehacker-mech (already-crewed vehicles)

Advisory/cosmetic — no gameplay impact, but affects client UX (dim/badge on unplayable cards). The `ConsequentialConditionNotMet` variant needs engine-side condition evaluation per priority point.

**Card specs:** kellan-planar-trailblazer, loot-exuberant-explorer, surgehacker-mech
**No existing issue.** Needs new issue.

### Transform (silent grpId mutation)

One implementation covers all DFCs and sagas. Two variants observed:

**In-place mutation (DFC):** grpId swap in Diff object, no ZoneTransfer, no dedicated annotation.
- **Card specs:** treasure-map, brasss-tunnel-grinder, concealing-curtains
- **Existing issue:** #191 (DFC / Saga flip wire)
- **Confirmed pattern:** Treasure Map 87432->87433, Concealing Curtains 78895->78896

**Exile-return transform (Saga chapter III):** Two-step ZoneTransfer with two ObjectIdChanged.
BF->Exile (category=`Exile`) then Exile->BF (category=`Return`) with grpId change.
- **Card specs:** tribute-to-horobi (79552->79553, full lifecycle traced)
- **Existing issue:** #191
- **Key finding:** Both old IDs persist in Limbo (zone 30) for animation continuity

### Cast-from-non-hand

ActionMapper must offer cast actions for cards in zones other than Hand.

| Origin zone | Action type | Category | Card specs |
|-------------|------------|----------|-----------|
| GY (33) | Cast (abilityGrpId distinguishes flashback) | CastSpell | think-twice, electroduplicate |
| GY (33) | CastAdventure (actionType=16) | CastSpell | ratcatcher-trainee |
| Exile (29) | CastSpell | CastSpell | ratcatcher-trainee |

**Existing issue:** #173 (Cast adventure: 5 code gaps)

### AbilityWordActive annotation

Persistent annotation tracking ability word conditions.

| Word | Timing | Card specs |
|------|--------|-----------|
| Threshold (value=GY count, threshold=7) | every diff | kiora-the-rising-tide |
| Descended (no value/threshold fields) | presence flag | brasss-tunnel-grinder |
| Case solve (value=condition count, threshold=N) | **fires at cast (on stack)** | case-of-the-pilfered-proof |
| Raid (no threshold, names permanents + active triggers) | end-step trigger | perforating-artist |

**Existing issue:** #177 (AbilityWordActive annotation not emitted)

### Qualification annotation (type 42)

Persistent annotation for continuous effects and ability restrictions. Four distinct usages confirmed:

| QualificationType | Context | Card specs |
|-------------------|---------|-----------|
| 47 | Adventure exile marker (enables cast-from-exile) | ratcatcher-trainee |
| 21 | "Loses all abilities + doesn't untap" (aura continuous effect) | tamiyos-compleation |
| 40 | Menace keyword grant | concealing-curtains, nullpriest-of-oblivion |
| CantBeBlockedByObjects | Evasion grant ("can't be blocked except by...") | tatsunari-toad-rider |

**Card specs:** tamiyos-compleation, tatsunari-toad-rider, ratcatcher-trainee, concealing-curtains, nullpriest-of-oblivion

### RemoveAbility annotation (type 23)

Multi-type persistent annotation: `[RemoveAbility, LayeredEffect]`. First wire data.

- affectorId = aura iid, affectedIds = target creature
- Detail: key = target instanceId (string), value = "all"
- effect_id links to the LayeredEffectCreated transient

**Card specs:** tamiyos-compleation
**Existing rosetta entry:** type 23, listed as MISSING. Now has concrete wire shape.

### AddAbility annotation (type 9)

Persistent annotation for granted abilities. Two confirmed usages:

| Context | grpId | Card specs |
|---------|-------|-----------|
| Spell grants "dies/exile -> return" trigger | 153272 | ashnods-intervention |
| Conditional self-granting static (first strike) | 6 | ratcatcher-trainee |
| Indestructible until EOT (self-grant) | 104 | immersturm-predator |

**Existing rosetta entry:** type 9, listed as MISSING. Now has concrete wire shape.

### GY->BF return (category = "Return")

Transfer category **confirmed: "Return"** via Sun-Blessed Healer kicked ETB (gsId 278) and
Tribute to Horobi exile-return transform (Exile->BF category=`Return`).

**Card specs needing this:** nullpriest-of-oblivion, cauldron-familiar, cleric-class, archfiends-vessel, sun-blessed-healer, tribute-to-horobi

### PayCostsReq variants

Five distinct PayCostsReq shapes confirmed:

| promptId | Cost type | Card specs |
|----------|-----------|-----------|
| 1024 | Mandatory additional cost (discard) | mardu-outrider |
| 1074 | Sacrifice-as-cost (activated ability) | immersturm-predator |
| 1010 | Equip targeting (SelectTargetsReq) | black-mages-rod (standard equip) |
| Select (effectCostType) | Crew (power-weighted creature selection) | surgehacker-mech |
| — | Sacrifice Food (GY activated ability) | cauldron-familiar (unobserved) |

**Existing issue:** #192 (Mandatory additional cost)

### Copy tokens (expanded)

Copy token carries source creature's grpId (not a distinct token grpId). Confirmed annotations:

- `TemporaryPermanent` pAnn — marks EOT-sacrifice tokens
- EOT-sacrifice trigger fires at token ETB (AbilityInstanceCreated immediately)
- If source is adventure creature, copy also gets Adventure proxy object
- `isCopy: true` flag set on gameObject
- Copy tokens inherit all triggered abilities (fire independently)
- parentId on token = ability instance iid that created it

**Card specs:** electroduplicate, homunculus-horde, thousand-faced-shadow
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

**Card specs:** surgehacker-mech — **Done (PR #272)**

### Vehicle / Crew mechanic

Vehicles are artifacts that become creatures when crewed. **Done (PR #272)** for type-change, CrewedThisTurn, ModifiedType + LayeredEffect. PayCostsReq for interactive crew is a follow-up.

**Card specs:** surgehacker-mech

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

### GenericChoice SelectNReq

Forge's `GenericChoice` bridges to Arena's SelectNReq with `idType: PromptParameterIndex`. Branch indices are abstract (not instanceIds). Server dynamically prunes unavailable branches. Two-phase SelectNReq for sacrifice: branch selection, then permanent selection.

**Card specs:** perforating-artist
**Existing issue:** #174 (Raid punisher: two-level sequential SelectN not handled)

### Reveal opponent hand infrastructure

RevealedCard proxy synthesis, hand visibility flip, SelectNReq with filtered `ids` vs `unfilteredIds`, RevealedCardDeleted cleanup.

**Card specs:** duress, concealing-curtains
**Existing issue:** #256 (not visible in list — may be closed; was referenced in concealing-curtains)

### ChoiceResult annotation

New annotation type for sacrifice/discard choices.

**Card specs:** perforating-artist
**Existing issue:** #171 (ChoiceResult annotation not emitted)

## Tier 3 — New mechanics (card-specific but reusable patterns)

### Adventure lifecycle

Multi-step: CastAdventure (actionType=16) -> adventure proxy object (type=Adventure) on stack -> resolve to Exile (category=Resolve) -> Qualification pAnn (QualificationType=47) -> cast creature from Exile (CastSpell zone_src=29).

**Card specs:** ratcatcher-trainee (full loop observed)
**Existing issue:** #173 (Cast adventure)

### Impulse draw ("exile top, play this turn")

Forge's `DB$ Dig` + `MayPlay$ True` continuous effect. Requires: ZoneTransfer(Library->Exile) with reveal, CardRevealed annotation (#47), Qualification pAnn marking the exiled card as playable this turn. **Unobserved** — Kellan never dealt combat damage as Detective.

**Card specs:** kellan-planar-trailblazer
**No existing issue.** Blocked on a game where the trigger fires.

### Animate ability (type change + keyword grant)

Forge's `AB$ Animate` changes creature types, P/T, and grants keywords. The engine fires `GameEventCardStatsChanged`. The `ConditionPresent$` gate and `RemoveCreatureTypes$` override need testing for activated abilities.

**Card specs:** kellan-planar-trailblazer

### Job select (ETB token + auto-attach)

Triggered ability creates token and attaches equipment in one resolution. No player interaction.

**Card specs:** black-mages-rod (single data point)

### GY activated abilities

Server offers Activate in GSM actions array when card enters GY. `inactiveActions` for abilities with unmet costs.

**Card specs:** cauldron-familiar (observed offer, return unobserved), thousand-faced-shadow (ninjutsu in inactiveActions)

### Class enchantment leveling

Activated abilities for level-up, all abilities sent upfront, `inactiveActions` with `disqualifyingSourceId` gates progression, ClassLevel pAnn (type=88).

**Card specs:** cleric-class (no mechanics exercised)

### Ninjutsu

Hand->BF attacking (bypasses stack). Swap unblocked attacker back to hand. Offered via `inactiveActions` — JSONL decoder drops this field (tooling gap).

**Card specs:** thousand-faced-shadow (unobserved)

### Sacrifice-as-activated-ability

Distinct from sacrifice-as-cost. Immersturm Predator: sacrifice another creature is the cost (PayCostsReq promptId=1074), then tap + indestructible on resolution.

**Card specs:** immersturm-predator

### ETB/LTB shared grpId

Cryogen Relic: ETB draw and LTB draw share abilityGrpId 190898. Server disambiguation unknown — may rely on zone context. LTB unobserved.

**Card specs:** cryogen-relic

### Gain control (AllValid targeting)

`GainControl | AllValid$ Rat.token` — "all matching permanents" with no target selection.
Wire shape: ControllerChanged per affected permanent + one LayeredEffectCreated.

**Card specs:** tribute-to-horobi

### Conditional triggered abilities

Intervening-if conditions ("if you don't control X") are server-side. Trigger suppression
confirmed: no AbilityInstanceCreated when condition fails.

**Card specs:** tatsunari-toad-rider (Keimi check), archfiends-vessel (GY check — unobserved)

### Planeswalker phaseout protection

ETB end-step trigger -> PhasedOut -> zone 12. Phase-in at next untap (no ETB re-trigger).
loyaltyUsed field tracks activations per turn.

**Card specs:** kaito-shizuki

### Discard-as-cost (channel/additional)

Two patterns: channel (discard self, activated ability from hand) and mandatory additional cost
(discard other card, PayCostsReq promptId=1024). Both use standard `Discard` category.

**Card specs:** twinshot-sniper (channel), mardu-outrider (mandatory additional)

### Delayed triggered abilities

Spell grants a trigger via AddAbility pAnn. When the condition fires (creature dies/exiled), a new Ability object goes on stack with parentId=original creature. ZoneTransfer uses `Put` category for triggered returns, not `Resolve`.

**Card specs:** ashnods-intervention

### Library search (SearchReq/SearchResp handshake)

Pure UI handshake (GRE type 44). Search-to-hand uses category `Put` (not `Draw`). RevealedCard proxy + InstanceRevealedToOpponent pAnn (type 75). Shuffle with full OldIds/NewIds.

**Card specs:** bushwhack

### Fight mechanic

Mutual damage: each creature deals power damage to the other. Two-target SelectTargetsReq. No successful fight resolution captured — fight was countered in the only observed attempt.

**Card specs:** bushwhack (fight mode countered)

## Existing issues updated

| Issue | Evidence added |
|-------|---------------|
| #191 DFC / Saga flip wire | Treasure Map: grpId mutation confirmed. Tribute to Horobi: exile-return transform with double ObjectIdChanged, categories `Exile`/`Return`, old IDs in Limbo |
| #173 Cast adventure | Ratcatcher Trainee: full lifecycle, actionType=16, Qualification pAnn 47 |
| #177 AbilityWordActive | Kiora: threshold. Brass's: descended. Case: fires at cast, not resolve. Perforating Artist: Raid |
| #192 Mandatory additional cost | Mardu Outrider: PayCostsReq 1024. Immersturm: sacrifice 1074. Surgehacker: crew Select type |
| #245 CopyPermanent token | Electroduplicate: copy grpId = source grpId, TemporaryPermanent pAnn. Homunculus Horde: self-replicating copies with inherited triggers, `isCopy: true` flag |
| #200 Continuous effects | Tamiyo's Compleation: RemoveAbility+LayeredEffect pAnn shape. Tatsunari: Qualification for evasion |
| #174 Raid punisher SelectN | Perforating Artist: GenericChoice with PromptParameterIndex, two-phase SelectNReq, branch pruning |
| #171 ChoiceResult | Perforating Artist: affectorId=permanent, Choice_Value=instanceId, Choice_Sentiment=1 |

## New issues needed

| Mechanic | Scope | Priority | Unblock count |
|----------|-------|----------|---------------|
| OptionalActionMessage handler | "May" triggers — pendingMessageCount=1 + OptionalResp | High — horizontal blocker | 3+ |
| Static cost reduction in action builder | Query Forge CostAdjustment when building Cast actions, bake reduced cost into manaCost | Medium — FDN staple | 2+ |
| Draw-count triggers | Per-turn per-player draw counting, fire "draw Nth card" triggers | Medium | 1 (homunculus-horde) |
| AdjustLandPlays | Per-turn land play count tracking | Medium — FDN staple | 1 (loot) |
| DigUntil resolution | Reveal cards until match, RevealedCard objects, shuffle rest | Medium | 1 (spinner-of-souls) |
| RevealedCard lifecycle | RevealedCardCreated/Deleted annotations + proxy objects | High — cross-cutting | 5+ |
| ShouldntPlay (type 53) | Advisory annotation — 4 reasons documented | Low — cosmetic | 3 |
| Channel | Hand-zone activation + discard-as-cost catalog entry | Low — NEO specific | 1 |
| Phasing (PhasedOut/PhasedIn) | Annotation types 95/96 + zone 12 + GameEventCardPhased wiring | Medium — blocks planeswalkers | 1 |
| Dig (look at top N, choose M) | Server-internal resolution, Put category, optional selection | Medium | 1 (loot) |

## Trace gaps — next game targets

| Mechanic | What to play | Why |
|----------|-------------|-----|
| Kellan impulse draw | Kellan as Detective, deal combat damage to player | Exercise `DB$ Dig` exile + MayPlay trigger |
| "May" trigger decline | Spinner of Souls — decline the DigUntil | `OptionalResp(CancelNo)` -> `AbilityInstanceDeleted` without resolution |
| DigUntil empty library | Spinner of Souls — dig with 0 creatures remaining | Edge case: all cards go to bottom, nothing goes to hand |
| Loot activation decline | Loot — activate Dig, decline all 6 cards | `Optional$ True` on Dig with no legal/chosen card |
| Third land play source | Loot — investigate gs=632 third PlayLand | Identify source of unexpected extra land play |
| Kicked ETB -> GY return | Nullpriest of Oblivion (kick it!) | Confirm "Return" category matches Sun-Blessed Healer |
| GY activation + Food sacrifice | Cauldron Familiar + any Food maker | GY->BF return via activated ability |
| Conditional ETB (from GY) | Archfiend's Vessel + Raise Dead | Self-exile + Demon token creation |
| Class leveling | Cleric Class (reach level 2+) | ClassLevel pAnn, level-up resolution shape |
| Ninjutsu | Thousand-Faced Shadow (activate it!) | Hand->BF attacking, swap action type |
| LTB trigger | Cryogen Relic (sacrifice it) | ETB/LTB disambiguation with shared grpId |
| Transform (saga) | Brass's Tunnel-Grinder (3+ bore counters) | Confirm saga->land transform matches DFC pattern |
| -2 / -7 planeswalker abilities | Kaito Shizuki (activate -2 and -7) | Ninja token creation, emblem zone handling |
| Saga attack trigger | Echo of Death's Wail (sacrifice a creature) | May-sacrifice ability resolution |
| Fight resolution | Bushwhack fight mode (both targets survive to resolution) | DamageDealt annotation shape for mutual damage |
| Cost reduction variance | Goblin Electromancer or Baral on battlefield | Confirm same manaCost baking pattern |
| CopyPermanent of opponent creature | Electroduplicate targeting opponent's creature | Copy with different controller/owner semantics |
| Evasion grant variance | Other "can't be blocked by" cards | Confirm Qualification CantBeBlockedByObjects pattern |

## Spec consistency fixes applied

- **Language:** "recording" -> "game" across 14 older specs (tamiyos-compleation, tatsunari, kaito-shizuki, tribute-to-horobi, black-mages-rod, treasure-map, surgehacker-mech, cleric-class, bushwhack x4, brasss-tunnel-grinder, perforating-artist, duress, ashnods-intervention, kellan, spinner-of-souls)
- **Heading:** perforating-artist.md "## Recordings" -> "## Games"
- **Duplicate content:** removed "Kellan Modal Ability Investigation" section from scavenging-ooze.md (duplicate of kellan spec's comprehensive modal activation protocol section)
- **All 6 new specs** have Tooling Feedback sections (template compliant)
- **All 6 new specs** use "game" not "recording" consistently
