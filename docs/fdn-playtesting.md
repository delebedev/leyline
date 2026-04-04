---
summary: "Running log of FDN set playtesting: wire protocol observations, mechanic interactions (morbid, landfall, incubate), and conformance notes."
read_when:
  - "checking known issues with FDN set mechanics"
  - "looking for prior observations on a specific FDN card or mechanic"
  - "updating conformance notes after FDN playtesting"
---
# FDN Playtesting Findings

Running log of wire protocol observations, mechanic interactions, and conformance notes from FDN bot matches. Raw material for card specs, catalog updates, and implementation work.

## Sessions

| Date | Session | Archetype | Turns | Result | Key mechanics |
|------|---------|-----------|-------|--------|---------------|
| 2026-03-29 | `14-25-04` | Izzet U/R | 23 | P1 win | Incubation/hatch, threshold (Kiora), raid, prowess, mill |
| 2026-03-29 | `16-18-08` | Selesnya G/W | 18 | P1 win | Morbid, landfall, pay-X, trample overflow, trigger stacking |
| 2026-03-29 | `16-32-23` | Gruul R/G | 26 | P1 win | Waterknot lock, modal ETB, landfall doubling, raid (no-attack path) |
| 2026-03-29 | `16-45-39` | GW Rabbits | 20 | P1 win | Mass removal, SBA_UnattachedAura, OrderReq, aura bounce |
| 2026-03-29 | `16-55-19` | GW Angels | 32 | P1 win | 3× Day of Judgment, trigger fizzle, death triggers, charm sub-abilities |
| 2026-03-29 | `17-04-26` | WG Angels | 16 | P1 win | Bounce="Return", kicker recursion, double strike aura, SBA_UnattachedAura |

---

## Wire Protocol Findings

### Morbid

**Source:** `16-18-08`, Cackling Prowler (grp:93814) — fired T8/T9/T11/T12/T13

- `AbilityWordActive` annotation with `AbilityWordName="Morbid"`
- Boolean check — no value field (unlike threshold which tracks a count)
- Fires during combat phase when a creature died that turn
- +1/+1 counter resolves at end step
- `grp:None` on affectorId (iid:1 = seat object, not a card)
- Needletooth Pack (grp:93820) also has morbid — gets +2 counters instead of +1

### Landfall

**Source:** `16-18-08`, Elfsworn Giant + Mossborn Hydra + Primeval Bounty

- No dedicated "landfall triggered" annotation type
- Fires as `AbilityInstanceCreated` in the same GSM diff as `ZoneTransfer(PlayLand)`
- Multiple permanents each spawn their own ability instance in one diff
- Elfsworn Giant (grp:175868): landfall → 1/1 Elf Warrior token (grp:94174)
- Mossborn Hydra (grp:175873): landfall → doubles +1/+1 counters
- Primeval Bounty (grp:20153): landfall → +3 life

### Threshold (Kiora)

**Source:** `14-25-04`, Kiora the Rising Tide (grp:unknown) — tracked T9–T23

- `AbilityWordActive` with continuous value tracking: GY card count 0→11 over the game
- Value updates on every GY change (mill, discard, creature death)
- Threshold at 7: on attack with 7+ cards in GY → creates Scion of the Deep (grp:94168, 8/8 Legendary Octopus token)
- Token is permanent (not end-of-turn)
- `ResolutionStart` + `TokenCreated` in same gsId as ability resolution

### Pay X (Wildborn Preserver)

**Source:** `16-18-08`, Wildborn Preserver (grp:unknown) — T13/T14/T18

- Trigger: non-Human creature ETB → `OptionalActionMessage` → player chooses to pay
- Sequence: `OptionalActionMessage` → `OptionalActionResp` → `NumericInputReq` → `NumericInputResp` → `PayCostsReq` → `PerformAutoTapActionsResp` → ability on stack
- `AbilityWordActive` with `AbilityWordName="ValueOfX"` on the **ability instance** (not card)
- Value field = chosen X
- When X=0, still fires (player opts in but pays nothing)
- Three distinct trigger instances on T18 from multiple non-Human creatures entering simultaneously
- Each instance has its own `ValueOfX` persistent annotation

### Incubation / Hatch (Drake Hatcher)

**Source:** `14-25-04`, Drake Hatcher (grp:unknown) — T3–T21

- `counter_type=200` (incubation) lives on the creature itself — not a separate egg token
- Combat damage → `CounterAdded` with incubation counters
- Hatch = activated ability (abilityGrpId:175787) that removes incubation counters
- Wire: `CounterRemoved` type=200 in same diff as `AbilityInstanceCreated`
- Resolution creates 2/2 Flying Drake token
- Spending 3 counters to hatch (observed)

### Hydra Counter Doubling (Mossborn Hydra)

**Source:** `16-18-08`, Mossborn Hydra (grp:93820) — T6→T18

- Single `CounterAdded` event with `transaction_amount` = doubled total
- NOT two separate events (add then double)
- Progression: 1, 1, 2, 4, 8 across T6/T8/T12/T14/T16 → ended at 16/16
- ETB trigger (grp:175873) fires on each land drop

### Trigger Stacking

**Source:** `16-18-08`, multiple turns

- `SelectNreq` with `context=TriggeredAbility_c799`, `optionContext=Stacking`
- Fires when 2+ triggered abilities are pending simultaneously
- `min=1/max=N` where N = number of triggers to order
- Observed at T12 (Elfsworn Giant + Mossborn Hydra ETBs), T14 (Felidar Savior + Wildborn Preserver), T18 (Wildborn Preserver + Needletooth Pack)
- Both trigger instances presented as choices — player picks resolution order

### Trample Damage Assignment

**Source:** `16-18-08`, Mossborn Hydra 16/16 trample vs 1/2 Cat (T18)

- `AssignDamageReq` includes a **player slot** for trample overflow
- Defending player represented as `instanceId = seatId` (value `2`)
- Blocker slot: `minDamage = toughness`, `assignedDamage = minDamage`, no `maxDamage`
- Player slot: `maxDamage = overflow`, `assignedDamage = overflow`, no `minDamage`
- Both slots come pre-filled — client can accept as-is
- Consistent across early game (2 power, 1 overflow) and late game (16 power, 14 overflow)
- See #235 for implementation status

### Raid (Skyship Buccaneer, Goblin Boarders)

**Source:** `14-25-04`

- Skyship Buccaneer: raid ETB → draw a card (if attacked this turn)
- Goblin Boarders: raid ETB → +1/+1 counter
- No dedicated "raid" annotation observed — fires as standard conditional ETB

### Primeval Bounty Dual Triggers

**Source:** `16-18-08`, T16–T18

- T16: Primeval Bounty resolves (6-mana enchantment)
- T17: land drop → landfall clause fires (grp:20153) → +3 life
- T18: cast Needletooth Pack (creature spell) → creature clause fires (grp:20151) → 3/3 Beast token (grp:94185)
- Beast token entering triggers Wildborn Preserver (non-Human ETB) → pay-X chain
- Two distinct ability grpIds for the two clauses: 20153 (land), 20151 (creature)

### Death Trigger → Treasure (Gleaming Barrier)

**Source:** `16-18-08`, T7

- Gleaming Barrier (grp:116851) dies via `SBA_Damage`
- Death trigger → `AbilityInstanceCreated` → `TokenCreated` (Treasure token)
- Standard death trigger wire pattern

### ETB Fight (Affectionate Indrik)

**Source:** `16-18-08`, T11

- 4/4, ETB → fight target creature
- `SelectTargetsReq` for fight target selection
- Damage dealt as `DamageDealt` to target creature → `SBA_Damage` if lethal

### Bounce Category = "Return" (not "Bounce")

**Source:** `17-04-26`, Mischievous Pup ETB bounce — first recorded bounce effect

- BF→Hand `ZoneTransfer` uses `category="Return"`, not `"Bounce"`
- **Bug in our code:** `AnnotationBuilder` maps BF→Hand to `TransferCategory.Bounce` — server sends "Return"
- Confirmed in same session: Sun-Blessed Healer GY→BF also uses `category="Return"`
- No recording has ever confirmed "Bounce" as a real server category

### OrderReq (GRE type 17) — Trigger Ordering

**Source:** `16-45-39`, Raise the Past returning 2× Hare Apparent

- First recording of OrderReq for trigger stacking
- Wire: `orderReq { ids: [triggerIid1, triggerIid2] }` with `prompt.parameters[0] = {CardId, numberValue: <causingAbilityIid>}`
- Distinct from `SelectNreq` (optionContext=Stacking) seen in `16-18-08`
- Currently MISSING in `docs/rosetta.md` and `docs/systems-map.md`
- Both OrderReq and SelectNreq(Stacking) appear in the same game (`16-45-39`) — may serve different trigger-ordering roles

### DisqualifiedEffect (Waterknot no-untap)

**Source:** `16-32-23`, Waterknot on Goblin Boarders

- `DisqualifiedEffect` annotation: `affectorId=<waterknot_iid>, affectedIds=None, details=None`
- Fires every upkeep while the lock is active
- Multiple concurrent Waterknots each emit their own DisqualifiedEffect independently
- Represents a CantHappen replacement effect being applied

### Apothecary Stomper Modal ETB

**Source:** `16-32-23`, two casts — chose life gain (T14) and +1/+1 counters (T22)

- Both modal choices queued as separate `AbilityInstanceCreated` events on ETB
- Unchosen mode never gets `ResolutionStart` — silently dropped
- Sub-ability grpIds: 121504 (gain 4 life), 175863 (+1/+1 counters on target)
- Life gain: `ModifiedLife afid=<ability> aids=[1] det={life:4}` — affectedIds = seatId
- Counters: `SelectTargetsReq` → `CounterAdded` on target

### SBA_UnattachedAura (7 events, 3 sessions)

**Sources:** `16-45-39` (2), `16-55-19` (4), `17-04-26` (1)

- Wire shape always identical: `LayeredEffectDestroyed` → `ObjectIdChanged` → `ZoneTransfer(SBA_UnattachedAura)`
- `LayeredEffectDestroyed` count = number of continuous effects on the aura (River's Favor: 1, Angelic Destiny: 3, Twinblade Blessing: 1, Knight's Pledge: 1)
- No `affectorId` on the SBA ZoneTransfer (consistent across all events)
- Three trigger paths confirmed:
  - Host destroyed by targeted removal / combat damage
  - Host destroyed by mass removal (Day of Judgment)
  - Host bounced to hand (Mischievous Pup ETB)
- Aura always goes to GY regardless of how host left
- Angelic Destiny return-to-hand trigger does NOT fire on bounce (Forge script correctly restricts to `Destination$ Graveyard`)

### Primeval Bounty Noncreature Trigger Fizzle

**Source:** `16-55-19`, DoJ cast with Bounty on BF

- Trigger grpId 20152 (noncreature spell → +1/+1 counters on target creature)
- No `AbilityInstanceCreated` for 20152 visible after DoJ cast
- DoJ kills all creatures → no legal targets at trigger generation time
- Trigger appears to silently not generate (vs. generating and fizzling at resolution)
- Creature-cast (20151) and landfall (20153) triggers confirmed working normally

### Charm Sub-Ability grpId Pattern

**Source:** `16-32-23` + `16-55-19`, Sylvan Scavenging

- Card trigger grpId = 175880 (the end-step trigger)
- Stack ability grpId = 175879 (the chosen sub-effect, Raccoon token)
- `ResolutionStart/Complete` on stack uses the sub-ability grpId, not the card's trigger grpId
- Consistent with prior Charm-based card patterns

### Kicker CastingTimeOption

**Source:** `17-04-26`, Sun-Blessed Healer

- `CastingTimeOptionsReq` with `type=Kicker, grpId=9313` at cast
- Resolution: `CastingTimeOption type=3 kickerAbilityGrpId=9313` (transient annotation)
- Kicked ability creates `AbilityInstanceCreated` for grpId 175778 → GY→BF return

### Flamewake Phoenix Ferocious Return

**Source:** `14-25-04`, T21

- Begin Combat trigger from GY: `PayCostsReq` for {R} cost (same wire shape as ward)
- `ZoneTransfer` GY→BF with `category="Return"`
- Permanent gets `must_attack` + `SourceParent` persistent annotations
- `hasSummoningSickness: false` (has haste)

### Raid — Confirmed and Non-Confirmed Paths

**Source:** `14-25-04` (fired), `16-32-23` (Goblin Boarders cast without prior attack)

- `AbilityWordActive` pAnn with `AbilityWordName="Raid"` at resolution
- Goblin Boarders: `ReplacementEffect_803b` pAnn + `CounterAdded` when raid is active
- When raid NOT active (no attack that turn): `ReplacementEffect` pAnn still set up but no `CounterAdded` fires — correct behavior
- Forge script `CheckSVar$ RaidTest:Count$AttackersDeclared` matches

### LayeredEffect P/T Lifecycle

**Source:** `16-32-23`, Mossborn Hydra counter progression

- Each counter change: `LayeredEffectDestroyed` + `LayeredEffectCreated` in same diff
- Server recreates the P/T modification layer from scratch — no "update", always destroy+create
- Effect IDs are monotonic (7002→7009 across 8 events in one game)

---

## Confirmed Bugs

### Bounce category mismatch
- Our `AnnotationBuilder` emits `"Bounce"` for BF→Hand; real server sends `"Return"`
- First bounce recording: `17-04-26` Mischievous Pup
- See `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt` ~line 161

---

## Gaps to File

| Gap | Priority | Source |
|-----|----------|--------|
| Bounce category "Return" vs "Bounce" | High — wrong annotation | `17-04-26` |
| OrderReq (type 17) not implemented | Medium — trigger ordering | `16-45-39` |
| SBA_UnattachedAura annotation not emitted | Medium — 7 confirmed events | Multiple |
| Primeval Bounty noncreature fizzle — verify engine behavior | Low — edge case | `16-55-19` |

---

## Unobserved Mechanics (need dedicated sessions)

- Giada ETBReplacement — angel entering with Giada on BF (Giada always died first)
- Wildborn Preserver pay X=0 path (always paid >0)
- Prowess on Drake Hatcher (no noncreature spells while on BF in key windows)
- Sire of Seven Deaths Ward — Pay 7 life (opponent never targeted it)

---

## Cards Observed

### Game 1 (`14-25-04` — Izzet U/R)

| Card | Role | Mechanics |
|------|------|-----------|
| Drake Hatcher | 1/3, Vigilance, Prowess | Incubation counters on combat damage, activated hatch |
| Kiora, the Rising Tide | Legendary Merfolk Noble | Threshold (7 cards in GY) → Scion of the Deep token |
| Skyship Buccaneer | Flying | Raid: draw on ETB |
| Goblin Boarders | — | Raid: +1/+1 counter on ETB |
| Waterkin Shaman | 2/1 | Flying-pump ETB |
| Warden of Evos Isle | Flying | Cost reduction for flyers |
| Octoprophet | — | Scry 2 ETB |
| Bulk Up | Instant | Double power, flashback |
| Self-Reflection | — | Copy target creature |
| Inspiration from Beyond | — | Mill 3 + return instant/sorcery |
| Flamewake Phoenix | — | Token (copied via Self-Reflection) |

### Game 2 (`16-18-08` — Selesnya G/W)

| Card | Role | Mechanics |
|------|------|-----------|
| Mossborn Hydra | Elemental Hydra | ETB +1/+1, landfall doubles counters, trample |
| Cackling Prowler | 4/3 Hyena Rogue, Ward {2} | Morbid: +1/+1 at end step |
| Wildborn Preserver | 2/2 Flash Reach | Pay X on non-Human ETB → +X/+X |
| Primeval Bounty | 6-mana enchantment | Creature cast → 3/3 Beast; land → +3 life |
| Elfsworn Giant | 5/3 Reach | Landfall → 1/1 Elf Warrior token |
| Needletooth Pack | 4/5 | Morbid: +2 counters |
| Gleaming Barrier | 0/4 Defender | Death → Treasure token |
| Felidar Savior | 2/3 Lifelink | ETB: +1/+1 counters on 2 creatures |
| Resolute Reinforcements | 2/2 | ETB: Soldier token |
| Affectionate Indrik | 4/4 | ETB: fight |
| Wildwood Patrol | 4/2 Trample | — |
| Ilysian Caryatid | — | Mana dork |
| Generous Stray | — | ETB: draw |
| Treetop Warden | 2/2 | — |
| Rabid Bite | Sorcery | Creature deals damage to creature |
| Stony Strength | Instant | +1/+1 counter + untap |
| Blossoming Sands | Land | ETB: gain 1 life |

### Game 3 (`16-32-23` — Gruul R/G)

| Card | Role | Mechanics |
|------|------|-----------|
| Mossborn Hydra | Elemental Hydra | ETB +1/+1, landfall doubles (1→2→4→8→18→20) |
| Elfsworn Giant | 5/3 Reach | Landfall → Elf Warrior token |
| Wildborn Preserver | 2/2 Flash Reach | Pay X on non-Human ETB |
| Goblin Boarders | 3/2 | Raid: +1/+1 counter (observed NOT firing without attack) |
| Apothecary Stomper | 4/4 | Modal ETB: gain 4 life OR +1/+1 counters (both modes observed) |
| Cackling Prowler | 4/3 Ward {2} | Morbid (not exercised this game) |
| Sylvan Scavenging | Enchantment | EOT charm: Raccoon token (sub-grpId 175879) |
| Primeval Bounty | Enchantment | Not cast (stayed in hand) |
| Waterknot | Aura (opponent) | ETB tap + DisqualifiedEffect no-untap lock |
| Octoprophet | 3/2 | Scry 2 ETB via MultistepEffect |

### Game 4 (`16-45-39` — GW Rabbits)

| Card | Role | Mechanics |
|------|------|-----------|
| Hare Apparent | 2/2 | ETB: Rabbit tokens (15 copies in deck) |
| Day of Judgment | Sorcery | Destroy all creatures → SBA_UnattachedAura |
| Angelic Destiny | Aura | +4/+4, flying, first strike; SBA on bounce (3× LayeredEffectDestroyed) |
| River's Favor | Aura (opponent) | +1/+1; SBA_UnattachedAura after mass wipe |
| Raise the Past | Sorcery | GY→BF return ≤2 MV; triggered OrderReq for simultaneous ETBs |
| Mischievous Pup | 2/1 Flash | ETB bounce → category="Return" |
| Splinter, the Mentor | 2/2 Menace (opp) | LTB → Mutagen token (unobservable from P1 seat) |
| Needletooth Pack | 4/5 | Morbid (stayed in hand) |

### Game 5 (`16-55-19` — GW Angels)

| Card | Role | Mechanics |
|------|------|-----------|
| Day of Judgment | Sorcery (×3) | 3 wipes; 4× SBA_UnattachedAura across them |
| Giada, Font of Hope | 2/2 Flying Vigilance | ETBReplacement unexercised (died before angel cast) |
| Primeval Bounty | Enchantment | Creature trigger (Beast token), landfall (+3 life); noncreature fizzled |
| Gleaming Barrier | 0/4 Defender | Death → Treasure token (grpId 116851) confirmed |
| Sylvan Scavenging | Enchantment | EOT Raccoon tokens (×5); charm sub-grpId pattern |
| Sire of Seven Deaths | 6 keywords + Ward 7 life | Ward unexercised |
| Treetop Snarespinner | — | Activated +1/+1 counter (×3) |
| Knight's Pledge | Aura (opp, ×9) | SBA_UnattachedAura on host death |

### Game 6 (`17-04-26` — WG Angels)

| Card | Role | Mechanics |
|------|------|-----------|
| Mischievous Pup | 2/1 Flash | ETB bounce: category="Return" (first bounce recording) |
| Twinblade Blessing | Aura | Double strike; SBA_UnattachedAura on host death |
| Giada, Font of Hope | 2/2 Flying Vigilance | Died in combat; recursed by Sun-Blessed Healer |
| Sun-Blessed Healer | — | Kicked: GY→BF return (CastingTimeOption type=3) |
| Overrun | Sorcery | +3/+3 and trample (keyword grant — relevant to #31) |
| Felidar Savior | 2/3 Lifelink | ETB: +1/+1 on creatures |
